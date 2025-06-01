package io.jenkins.plugins.workfloworas;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import land.oras.Layer;
import land.oras.Manifest;
import land.oras.utils.Const;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@WireMockTest
class CpsOrasFlowDefinitionTest {

    @Test
    public void shouldRunPipeline(JenkinsRule jenkinsRule, WireMockRuntimeInfo wmRuntimeInfo) throws Exception {

        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String pipelineContent = "node { echo 'Hello World' }";
        Path pipeline = File.createTempFile("pipeline", ".groovy").toPath();
        Files.writeString(pipeline, pipelineContent);
        Layer layer = Layer.fromFile(pipeline);
        Manifest manifest = Manifest.empty()
                .withArtifactType(CpsOrasFlowDefinition.ARTIFACT_TYPE)
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
                "with digest sha256:27f611ccbb8e7760c3b752387206801bdd38413bc015f9276cafcbd8345d6893", b);
        jenkinsRule.assertLogContains("Hello World", b);
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
    }
}
