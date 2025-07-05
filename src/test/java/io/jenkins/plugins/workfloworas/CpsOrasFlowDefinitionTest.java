package io.jenkins.plugins.workfloworas;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import land.oras.ContainerRef;
import land.oras.Layer;
import land.oras.LocalPath;
import land.oras.Manifest;
import land.oras.Registry;
import land.oras.utils.Const;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@WithJenkins
@WireMockTest
@Testcontainers(disabledWithoutDocker = true)
class CpsOrasFlowDefinitionTest {

    @Container
    private final ZotContainer container = new ZotContainer().withStartupAttempts(3);

    @BeforeEach
    void before() {
        Registry registry =
                Registry.builder().insecure(this.container.getRegistry()).build();
        ContainerRef containerRef = ContainerRef.parse("%s/repo:latest".formatted(this.container.getRegistry()));
        registry.pushArtifact(
                containerRef, CpsOrasFlowDefinition.ARTIFACT_TYPE_REPO, LocalPath.of(Path.of("src/test/resources")));
    }

    @Test
    public void shouldRunPipelineWithSingleArtifact(JenkinsRule jenkinsRule, WireMockRuntimeInfo wmRuntimeInfo)
            throws Exception {

        WireMock wireMock = wmRuntimeInfo.getWireMock();
        Path pipeline = Path.of("src/test/resources/Jenkinsfile");
        String pipelineContent = Files.readString(pipeline);
        Layer layer = Layer.fromFile(pipeline);
        Manifest manifest = Manifest.empty()
                .withArtifactType(CpsOrasFlowDefinition.ARTIFACT_TYPE_SCRIPT)
                .withLayers(List.of(layer));
        String jsonManifest = manifest.toJson();

        // Mock returning the manifest
        wireMock.register(
                head(WireMock.urlPathMatching("/v2/pipeline/manifests/latest")).willReturn(WireMock.ok()));
        wireMock.register(get(WireMock.urlPathMatching("/v2/pipeline/manifests/latest"))
                .willReturn(WireMock.ok(jsonManifest)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, manifest.getDigest())
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE)
                        .withHeader(Const.CONTENT_LENGTH_HEADER, String.valueOf(jsonManifest.length()))));
        // Mock returning the layer
        wireMock.register(
                head(WireMock.urlPathMatching("/v2/pipeline/blobs/.*")).willReturn(WireMock.ok()));
        wireMock.register(get(WireMock.urlPathMatching("/v2/pipeline/blobs/.*"))
                .willReturn(WireMock.ok()
                        .withBody(pipelineContent)
                        .withHeader(Const.CONTENT_LENGTH_HEADER, String.valueOf(pipelineContent.length()))));

        WorkflowJob p = jenkinsRule.jenkins.createProject(WorkflowJob.class, "p");

        CpsOrasFlowDefinition def = new CpsOrasFlowDefinition(
                "%s/pipeline:latest".formatted(wmRuntimeInfo.getHttpBaseUrl().replaceAll("http://", "")));
        p.setDefinition(def);
        WorkflowRun b = jenkinsRule.buildAndAssertSuccess(p);
        jenkinsRule.assertLogContains(
                "with digest sha256:285d4286854b79ac57ff58f4916bc220a59de8b77d645813c6b3588d2ee5ed63", b);
        jenkinsRule.assertLogContains("Building...", b);
    }

    @Test
    public void shouldRunPipelineWithPackagedRepo(JenkinsRule jenkinsRule) throws Exception {
        WorkflowJob p = jenkinsRule.jenkins.createProject(WorkflowJob.class, "p1");
        CpsOrasFlowDefinition def = new CpsOrasFlowDefinition("%s/repo:latest".formatted(container.getRegistry()));
        def.setScriptPath("Jenkinsfile");
        p.setDefinition(def);
        WorkflowRun b = jenkinsRule.buildAndAssertSuccess(p);
        jenkinsRule.assertLogContains("Building...", b);
    }

    @Test
    void configRoundTripShouldPreserveDefinition(JenkinsRule jenkinsRule) throws Exception {
        String orasRef = "localhost:5000/pipeline:latest";

        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "test-pipeline");
        CpsOrasFlowDefinition def = new CpsOrasFlowDefinition(orasRef);
        p.setDefinition(def);

        // Simulate configuration round-trip
        jenkinsRule.configRoundtrip(p);

        WorkflowJob reloaded = jenkinsRule.jenkins.getItemByFullName("test-pipeline", WorkflowJob.class);
        assertEquals(CpsOrasFlowDefinition.class, reloaded.getDefinition().getClass());

        CpsOrasFlowDefinition reloadedDef = (CpsOrasFlowDefinition) reloaded.getDefinition();
        assertEquals(orasRef, reloadedDef.getContainerRef());
        assertEquals("", reloadedDef.getCredentialsId(), "Credentials ID should be empty by default");
    }
}
