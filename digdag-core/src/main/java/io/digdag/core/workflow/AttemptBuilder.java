package io.digdag.core.workflow;

import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import com.google.inject.Inject;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import io.digdag.client.config.Config;
import io.digdag.core.repository.StoredRevision;
import io.digdag.core.repository.StoredWorkflowDefinition;
import io.digdag.core.repository.StoredWorkflowDefinitionWithProject;
import io.digdag.core.repository.WorkflowDefinition;
import io.digdag.core.schedule.SchedulerManager;
import io.digdag.core.session.SessionMonitor;
import io.digdag.spi.Scheduler;
import io.digdag.spi.ScheduleTime;
import static io.digdag.core.agent.RuntimeParams.formatSessionTime;

public class AttemptBuilder
{
    private final SchedulerManager schedulerManager;
    private final SlaCalculator slaCalculator;

    @Inject
    public AttemptBuilder(SchedulerManager schedulerManager, SlaCalculator slaCalculator)
    {
        this.schedulerManager = schedulerManager;
        this.slaCalculator = slaCalculator;
    }

    public AttemptRequest buildFromStoredWorkflow(
            StoredRevision rev,
            StoredWorkflowDefinition def,
            Config overrideParams,
            ScheduleTime time)
    {
        return buildFromStoredWorkflow(
                rev, def, overrideParams, time,
                Optional.absent(), Optional.absent(), ImmutableList.of());
    }

    public AttemptRequest buildFromStoredWorkflow(
            StoredRevision rev,
            StoredWorkflowDefinition def,
            Config overrideParams,
            ScheduleTime time,
            Optional<String> retryAttemptName,
            Optional<Long> resumingAttemptId,
            List<Long> resumingTasks)
    {
        ZoneId timeZone = def.getTimeZone();
        Config sessionParams = buildSessionParameters(overrideParams, schedulerManager.tryGetScheduler(rev, def), time.getTime(), timeZone, Optional.absent());
        return ImmutableAttemptRequest.builder()
            .stored(AttemptRequest.Stored.of(rev, def))
            .workflowName(def.getName())
            .sessionMonitors(buildSessionMonitors(def, time.getRunTime(), timeZone))
            .timeZone(timeZone)
            .sessionParams(sessionParams)
            .retryAttemptName(retryAttemptName)
            .sessionTime(time.getTime())
            .resumingAttemptId(resumingAttemptId)
            .resumingTasks(resumingTasks)
            .build();
    }

    public AttemptRequest buildFromStoredWorkflow(
            StoredWorkflowDefinitionWithProject def,
            Config overrideParams,
            ScheduleTime time)
    {
        return buildFromStoredWorkflow(
                def, overrideParams, time,
                Optional.absent(), Optional.absent(), ImmutableList.of(), Optional.absent());
    }

    public AttemptRequest buildFromStoredWorkflow(
            StoredWorkflowDefinitionWithProject def,
            Config overrideParams,
            ScheduleTime time,
            Optional<String> retryAttemptName,
            Optional<Long> resumingAttemptId,
            List<Long> resumingTasks,
            Optional<Instant> lastSessionTime)
    {
        ZoneId timeZone = def.getTimeZone();
        Config sessionParams = buildSessionParameters(overrideParams, schedulerManager.tryGetScheduler(def), time.getTime(), timeZone, lastSessionTime);
        return ImmutableAttemptRequest.builder()
            .stored(AttemptRequest.Stored.of(def))
            .workflowName(def.getName())
            .sessionMonitors(buildSessionMonitors(def, time.getRunTime(), timeZone))
            .timeZone(timeZone)
            .sessionParams(sessionParams)
            .retryAttemptName(retryAttemptName)
            .sessionTime(time.getTime())
            .resumingAttemptId(resumingAttemptId)
            .resumingTasks(resumingTasks)
            .build();
    }

    private List<SessionMonitor> buildSessionMonitors(WorkflowDefinition def, Instant runTime, ZoneId timeZone)
    {
        // TODO move this to WorkflowExecutor?
        ImmutableList.Builder<SessionMonitor> monitors = ImmutableList.builder();
        if (def.getConfig().has("sla")) {
            Config slaConfig = def.getConfig().getNestedOrGetEmpty("sla");
            // TODO support multiple SLAs
            Instant triggerTime = slaCalculator.getTriggerTime(slaConfig, runTime, timeZone);
            monitors.add(SessionMonitor.of("sla", slaConfig, triggerTime));
        }
        return monitors.build();
    }

    private Config buildSessionParameters(Config overrideParams, Optional<Scheduler> sr, Instant sessionTime, ZoneId timeZone, Optional<Instant> lastExecutedSessionTime)
    {
        Config params = overrideParams.deepCopy();
        if (sr.isPresent()) {
            Instant lastSessionTime = sr.get().lastScheduleTime(sessionTime).getTime();
            Instant nextSessionTime = sr.get().nextScheduleTime(sessionTime).getTime();
            params.set("last_session_time", formatSessionTime(lastSessionTime, timeZone));
            params.set("next_session_time", formatSessionTime(nextSessionTime, timeZone));
            if (lastExecutedSessionTime.isPresent()) {
                // TODO: Come up with a better names for these parameters?

                // This is the last session that was processed. Normally this will be the immediately preceding session,
                // but it can be an earlier session if some were skipped due to skip_on_overtime = true.
                params.set("last_processed_session_time", formatSessionTime(lastExecutedSessionTime.get(), timeZone));

                // This is the first session time slot that should be included if the user wants to process any preceding skipped sessions.
                // Normally this is the same as the current session_time but can be an earlier session if some were skipped.
                Instant firstUnprocessedSessionTime = sr.get().nextScheduleTime(lastExecutedSessionTime.get()).getTime();
                params.set("first_unprocessed_session_time", formatSessionTime(firstUnprocessedSessionTime, timeZone));
            }
        }
        return params;
    }
}
