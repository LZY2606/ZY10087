package com.railway.sandbox.domain;

import com.railway.sandbox.config.SampleData;
import com.railway.sandbox.model.Topology;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopologyAnalysisTest {

    @Test
    void seedTopologyIsStructurallyValid() {
        Topology t = SampleData.yard();
        List<TopologyAnalysis.Issue> issues = new TopologyAnalysis(t).validate();
        assertTrue(issues.isEmpty(), "种子拓扑不应有结构问题: " + issues.stream().map(i -> i.code + ":" + i.ref).toList());
    }

    @Test
    void detectsDanglingConnection() {
        Topology t = SampleData.yard();
        t.sections.add(new Topology.Section("SX", "p1", "ghost"));
        TopologyAnalysis.Issue issue = new TopologyAnalysis(t).validate().stream()
                .filter(i -> i.code.equals("DANGLING")).findFirst().orElseThrow();
        assertTrue(issue.ref.contains("ghost"));
    }

    @Test
    void detectsDirectionMismatchInRoute() {
        Topology t = SampleData.yard();
        t.routes.add(new Topology.RouteDef("RBAD", "坏进路", "SigR1",
                List.of("S2", "S3"), java.util.Map.of("SW1", Topology.SwitchPosition.MINUS)));
        List<TopologyAnalysis.Issue> issues = new TopologyAnalysis(t).validate();
        assertTrue(issues.stream().anyMatch(i -> i.code.equals("DIRECTION")));
        // 路径不连续时无法再评估道岔几何，DIRECTION 即终止信号
    }

    @Test
    void detectsUnreachableSection() {
        Topology t = SampleData.yard();
        t.points.add(new Topology.Point("u1", false, 0.0, 0.0));
        t.points.add(new Topology.Point("u2", false, 10.0, 0.0));
        t.sections.add(new Topology.Section("SU", "u1", "u2"));
        TopologyAnalysis.Issue issue = new TopologyAnalysis(t).validate().stream()
                .filter(i -> i.code.equals("UNREACHABLE")).findFirst().orElseThrow();
        assertEquals("SU", issue.ref);
    }

    @Test
    void detectsSwitchPositionDisagreement() {
        Topology t = SampleData.yard();
        // R1 goes PLUS through SW1; claim MINUS
        t.routes.clear();
        t.routes.add(new Topology.RouteDef("R1", "正线", "SigR1",
                List.of("S0", "S2"), java.util.Map.of("SW1", Topology.SwitchPosition.MINUS)));
        TopologyAnalysis.Issue issue = new TopologyAnalysis(t).validate().stream()
                .filter(i -> i.code.equals("SWITCH_MISMATCH")).findFirst().orElseThrow();
        assertTrue(issue.message.contains("MINUS"));
    }
}
