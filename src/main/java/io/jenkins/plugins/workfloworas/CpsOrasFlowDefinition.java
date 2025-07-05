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
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import hudson.slaves.WorkspaceList;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.kohsuke.stapler.verb.POST;

@PersistIn(JOB)
public class CpsOrasFlowDefinition extends FlowDefinition {

    public static final ArtifactType ARTIFACT_TYPE_SCRIPT =
            ArtifactType.from("application/vnd.jenkins.pipeline.manifest.v1+json");

    public static final ArtifactType ARTIFACT_TYPE_REPO =
            ArtifactType.from("application/vnd.jenkins.repo.manifest.v1+json");

    /**
     * Credentials ID to retrieve the pipeline script
     */
    private String credentialsId;

    /**
     * Reference to the container in which the flow is defined such as my-registry/my-container:latest
     */
    private final String containerRef;

    /**
     * Optional path to the script inside the container.
     */
    private String scriptPath;

    @DataBoundConstructor
    public CpsOrasFlowDefinition(String containerRef) {
        this.containerRef = containerRef;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public String getContainerRef() {
        return containerRef;
    }

    public String getScriptPath() {
        return scriptPath;
    }

    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setScriptPath(String scriptPath) {
        this.scriptPath = scriptPath;
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
        ensureArtifactType(scriptPath, manifest);
        String digest = manifest.getLayers().get(0).getDigest();
        if (digest == null || digest.isEmpty()) {
            throw new IllegalArgumentException("No digest found for the container reference: " + containerRef);
        }
        if (!hasScriptPath(scriptPath)) {
            listener.getLogger()
                    .printf("Using pipeline script from container %s with digest %s%n", this.containerRef, digest);
            try (InputStream is = registry.fetchBlob(containerRef.withDigest(digest))) {
                return new CpsFlowExecution(new String(is.readAllBytes(), StandardCharsets.UTF_8), true, owner);
            }
        } else {
            FilePath dir = getDownloadFolder(owner);
            Computer computer = Jenkins.get().toComputer();
            if (computer == null) {
                throw new IOException(Jenkins.get().getDisplayName() + " may be offline");
            }
            listener.getLogger()
                    .printf(
                            "Using pipeline script %s from container %s with digest %s%n",
                            this.scriptPath, this.containerRef, digest);
            try (WorkspaceList.Lease lease = computer.getWorkspaceList().allocate(dir)) {
                Path scriptPathFile = Path.of(this.scriptPath).normalize();
                Path remote = Path.of(lease.path.getRemote());
                Path resolved = remote.resolve(scriptPathFile).normalize();
                if (!resolved.startsWith(remote)) {
                    throw new SecurityException("Only script path inside archive can be selected: " + scriptPathFile);
                }
                registry.pullArtifact(containerRef, remote, true);
                if (!Files.exists(resolved)) {
                    throw new IOException("Script path does not exist in the container: " + scriptPathFile);
                }
                String content = Files.readString(resolved);
                Util.deleteRecursive(remote.toFile());
                return new CpsFlowExecution(content, true, owner);
            }
        }
    }

    private static boolean hasScriptPath(String scriptPath) {
        return scriptPath != null && !scriptPath.isEmpty();
    }

    private FilePath getDownloadFolder(FlowExecutionOwner owner) throws IOException {
        FilePath dir;
        if (owner.getExecutable().getParent() instanceof TopLevelItem) {
            FilePath baseWorkspace = Jenkins.get()
                    .getWorkspaceFor((TopLevelItem) owner.getExecutable().getParent());
            if (baseWorkspace == null) {
                throw new IOException(Jenkins.get().getDisplayName() + " may be offline");
            }
            dir = baseWorkspace.withSuffix(getFilePathSuffix() + "cps");
        } else {
            throw new AbortException("Cannot check out in non-top-level build");
        }
        return dir;
    }

    private static String getFilePathSuffix() {
        return System.getProperty(WorkspaceList.class.getName(), "@");
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

    private static void ensureArtifactType(String scriptPath, Manifest manifest) {
        if (!hasScriptPath(scriptPath)
                && !Objects.equals(
                        ARTIFACT_TYPE_SCRIPT.getMediaType(),
                        manifest.getArtifactType().getMediaType())) {
            throw new IllegalArgumentException(
                    "The container reference does not point to a valid pipeline manifest. Make sure to set %s artifact type when pushing the artifact. Found artifact type %s instead"
                            .formatted(ARTIFACT_TYPE_SCRIPT, manifest.getArtifactType()));
        }
        if (hasScriptPath(scriptPath)
                && !Objects.equals(
                        ARTIFACT_TYPE_REPO.getMediaType(),
                        manifest.getArtifactType().getMediaType())) {
            throw new IllegalArgumentException(
                    "The container reference does not point to a valid repository manifest. Make sure to set %s artifact type when pushing the artifact. Found artifact type %s instead"
                            .formatted(ARTIFACT_TYPE_REPO, manifest.getArtifactType()));
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
        @POST
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
