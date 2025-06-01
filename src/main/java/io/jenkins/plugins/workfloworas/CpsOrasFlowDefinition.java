package io.jenkins.plugins.workfloworas;

import static org.jenkinsci.plugins.workflow.cps.persistence.PersistenceContext.JOB;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import jenkins.model.Jenkins;
import land.oras.ArtifactType;
import land.oras.ContainerRef;
import land.oras.Manifest;
import land.oras.Registry;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.CpsFlowFactoryAction2;
import org.jenkinsci.plugins.workflow.cps.persistence.PersistIn;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinitionDescriptor;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

@PersistIn(JOB)
public class CpsOrasFlowDefinition extends FlowDefinition {

    public static final ArtifactType ARTIFACT_TYPE =
            ArtifactType.from("application/vnd.jenkins.pipeline.manifest.v1+json");

    /**
     * Credentials ID to retrieve the pipeline script
     */
    private String credentialsId;

    /**
     * Reference to the container in which the flow is defined such as my-registry/my-container:latest
     */
    private final String containerRef;

    @DataBoundConstructor
    public CpsOrasFlowDefinition(String containerRef) {
        this.containerRef = containerRef;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public String getContainerRef() {
        return containerRef;
    }

    @Override
    public FlowExecution create(FlowExecutionOwner owner, TaskListener listener, List<? extends Action> actions)
            throws Exception {

        // Allow replay
        for (Action a : actions) {
            if (a instanceof CpsFlowFactoryAction2) {
                return ((CpsFlowFactoryAction2) a).create(this, owner, actions);
            }
        }

        Queue.Executable executable = owner.getExecutable();
        if (!(executable instanceof Run<?, ?> build)) {
            throw new IOException("Can only pull a Jenkinsfile in a run");
        }
        Registry registry = buildRegistry(build.getParent(), credentialsId);
        Credentials credentials = getCredentials(build.getParent(), this.credentialsId);
        if (credentials != null) {
            CredentialsProvider.track(build, credentials);
        }
        ContainerRef containerRef = ContainerRef.parse(this.containerRef);
        Manifest manifest = registry.getManifest(containerRef);
        ensureArtifactType(manifest);
        String digest = manifest.getLayers().get(0).getDigest();
        listener.getLogger()
                .printf("Using pipeline script from container %s with digest %s%n", this.containerRef, digest);
        if (digest == null || digest.isEmpty()) {
            throw new IllegalArgumentException("No digest found for the container reference: " + containerRef);
        }
        try (InputStream is = registry.fetchBlob(containerRef.withDigest(digest))) {
            return new CpsFlowExecution(new String(is.readAllBytes(), StandardCharsets.UTF_8), true, owner);
        }
    }

    private static Registry buildRegistry(Item item, String credentialsId) {
        Registry.Builder builder = Registry.builder();
        if (credentialsId == null || credentialsId.isEmpty()) {
            return builder.insecure().build();
        }
        UsernamePasswordCredentials credentials = getCredentials(item, credentialsId);
        if (credentials == null) {
            throw new IllegalArgumentException("No credentials found with ID: " + credentialsId);
        }

        return builder.defaults(
                        credentials.getUsername(), credentials.getPassword().getPlainText())
                .build();
    }

    public static @Nullable StandardUsernamePasswordCredentials getCredentials(Item item, String credentialsId) {
        if (credentialsId == null || credentialsId.isEmpty()) {
            return null;
        }
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItem(
                        StandardUsernamePasswordCredentials.class, item, ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.allOf(
                        CredentialsMatchers.withId(credentialsId),
                        CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)));
    }

    private static void ensureArtifactType(Manifest manifest) {
        if (!Objects.equals(
                ARTIFACT_TYPE.getMediaType(), manifest.getArtifactType().getMediaType())) {
            throw new IllegalArgumentException(
                    "The container reference does not point to a valid pipeline manifest. Make sure to set application/vnd.jenkins.pipeline.manifest.v1+json artifact type when pushing the artifact");
        }
    }

    @Extension
    @Symbol("cpsOras")
    @SuppressWarnings("unused")
    public static class DescriptorImpl extends FlowDefinitionDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Pipeline script from ORAS";
        }

        @SuppressWarnings("unused")
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
            final StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialsId);
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(credentialsId);
                }
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            item,
                            StandardUsernameCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.instanceOf(StandardUsernameCredentials.class))
                    .includeCurrentValue(credentialsId);
        }
    }
}
