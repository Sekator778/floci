package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.ProvisionContext.NamedChange;
import io.floci.aws.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.floci.aws.services.cloudwatch.dashboards.model.Dashboard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Provisions {@code AWS::CloudWatch::Dashboard}. Alarms belong to {@link CloudWatchCfnProvisioner};
 * this class takes only the dashboards service so the test fixture can wire it from that service.
 *
 * <p>The dashboard name is the physical id and {@code Ref}, generated when the template gives none
 * and kept across in-place updates. The engine compares template properties and treats
 * {@code DashboardName} as create-only. An in-place update puts the body again and drives the tags
 * to the template's set, since {@code PutDashboard} leaves an existing dashboard's tags alone.
 * The update keeps the prior body and tags on the resource so a failed stack update can put them
 * back. A name change, including dropping an explicit name for a generated one, creates a new
 * dashboard. The {@link ReplacementCleanup} record tracks the displaced dashboard for deletion when
 * the update commits or restoration on rollback.
 *
 * <p>The schema declares no read-only properties, so there is nothing for {@code Fn::GetAtt}.
 */
@ApplicationScoped
public class CloudWatchDashboardCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::CloudWatch::Dashboard";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DASHBOARD_NAME_MAX_LENGTH = 255;
    /** The registry schema's {@code maxItems} for Tags, which CloudFormation checks before the handler runs. */
    private static final int MAX_TAGS = 50;

    private final CloudWatchDashboardsService dashboardsService;

    @Inject
    public CloudWatchDashboardCfnProvisioner(CloudWatchDashboardsService dashboardsService) {
        this.dashboardsService = dashboardsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.DASHBOARD_UPDATE_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            // The unwind below undoes this update; only ContinueUpdateRollback retries the delete
            // the last rollback could not make.
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String explicitName = ctx.resolveOptional(props, "DashboardName");
        if (explicitName != null && !explicitName.isBlank() && explicitName.length() > DASHBOARD_NAME_MAX_LENGTH) {
            throw new AwsException("ValidationError",
                    TYPE + " DashboardName must be at most " + DASHBOARD_NAME_MAX_LENGTH + " characters", 400);
        }
        // The body is a string on the wire; a template may still write it as a JSON object.
        String body = props == null ? null : ctx.engine().resolveJsonAttribute(props.path("DashboardBody"));
        if (body == null || body.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires DashboardBody", 400);
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("ValidationError",
                    TYPE + " Tags must hold at most " + MAX_TAGS + " entries, got " + tags.size(), 400);
        }
        NamedChange change = ctx.changes(TYPE, props, explicitName, r.getLogicalId(), DASHBOARD_NAME_MAX_LENGTH,
                false, UnaryOperator.identity());
        String name = change.name();
        boolean inPlace = ctx.isUpdate() && !change.replaces();

        if (inPlace) {
            snapshotBeforeUpdate(r, name, ctx.region());
        }
        try {
            // PutDashboard creates or replaces the body in full, and applies the tags on create only.
            Dashboard dashboard = dashboardsService.putDashboard(name, body, tags, ctx.region());
            if (inPlace) {
                reconcileTags(dashboard.getDashboardArn(), tags, ctx.region());
            }
        } catch (RuntimeException failure) {
            unwind(r, name, failure);
            throw failure;
        }
        r.setPhysicalId(name);
        ReplacementCleanup.record(r, ctx, attributesBefore, change.replaces());
    }

    /**
     * A provision that changed the dashboard and then failed undoes itself. CloudFormationService
     * puts the resource the stack held before the attempt back in its place, and that object never
     * carried the snapshot taken here, so it marks the resource restored and the rollback walker
     * skips {@link #rollbackUpdate}: without this the stack reports a completed rollback while the
     * new body, or a half-reconciled set of tags, is still live. The stack must report the original
     * failure, so an unwind that cannot restore is attached to it and recorded as a rollback
     * failure, which ends the stack in UPDATE_ROLLBACK_FAILED rather than claiming the prior
     * dashboard is intact. The unwind repeats the tag call the update just made, so it can come
     * back carrying the very exception that interrupted the update; a throwable cannot be
     * suppressed under itself, and the failure is recorded before the two are joined so that
     * record never depends on it.
     */
    private void unwind(StackResource r, String name, RuntimeException failure) {
        try {
            rollbackUpdate(r);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
        } catch (RuntimeException unwindFailure) {
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                    "Could not roll back the update of dashboard " + name + ": " + unwindFailure.getMessage());
            if (unwindFailure != failure) {
                failure.addSuppressed(unwindFailure);
            }
        }
    }

    private void reconcileTags(String dashboardArn, Map<String, String> desired, String region) {
        Map<String, String> current = dashboardsService.listTagsForResource(dashboardArn, region);
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            dashboardsService.untagResource(dashboardArn, stale, region);
        }
        if (!desired.isEmpty()) {
            dashboardsService.tagResource(dashboardArn, desired, region);
        }
    }

    /**
     * Keeps the body and tags the dashboard has before an in-place update changes them, so
     * {@link #rollbackUpdate} can put them back. A dashboard that is already gone is recorded as
     * absent, so a rollback removes the one the update created in its place.
     */
    private void snapshotBeforeUpdate(StackResource r, String name, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("name", name);
        try {
            Dashboard current = dashboardsService.getDashboard(name, region);
            snapshot.put("body", current.getDashboardBody());
            ObjectNode tags = snapshot.putObject("tags");
            current.getTags().forEach(tags::put);
        } catch (AwsException e) {
            if (!"ResourceNotFound".equals(e.getErrorCode())) {
                throw e;
            }
            snapshot.put("absent", true);
        }
        r.getAttributes().put(CfnRollback.DASHBOARD_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        // ResourceNotFound is CloudWatch's own code for a dashboard that is already gone.
        CfnDeletes.safeDelete("CloudWatch dashboard", physicalId,
                () -> dashboardsService.deleteDashboards(List.of(physicalId), region),
                "ResourceNotFound");
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.DASHBOARD_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record and an in-place update from the snapshot
     * taken before it: the body is put back and the tags driven to the set the snapshot holds.
     * With neither on the resource the provision failed before it changed anything, since every
     * mutation is preceded by the snapshot or followed by the record, so there is nothing to undo.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        // The snapshot is spent only once the restore succeeded: a restore that throws leaves it in
        // place for the next attempt instead of reporting a rollback that never happened.
        String raw = resource.getAttributes().get(CfnRollback.DASHBOARD_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the dashboard update snapshot for "
                    + resource.getLogicalId(), e);
        }
        String region = snapshot.path("region").asText();
        String name = snapshot.path("name").asText();
        if (snapshot.path("absent").asBoolean(false)) {
            delete(TYPE, name, region);
        } else {
            Map<String, String> tags = new LinkedHashMap<>();
            snapshot.path("tags").fields().forEachRemaining(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
            Dashboard restored = dashboardsService.putDashboard(name, snapshot.path("body").asText(), tags, region);
            reconcileTags(restored.getDashboardArn(), tags, region);
        }
        resource.getAttributes().remove(CfnRollback.DASHBOARD_UPDATE_SNAPSHOT_ATTR);
        return true;
    }
}
