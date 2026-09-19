package com.railway.sandbox.service;

import com.railway.sandbox.config.SampleData;
import com.railway.sandbox.model.RuleSet;
import com.railway.sandbox.model.Topology;
import com.railway.sandbox.repo.ApprovalEntity;
import com.railway.sandbox.repo.RuleSetEntity;
import com.railway.sandbox.repo.ScenarioEntity;
import com.railway.sandbox.repo.TopologyEntity;
import com.railway.sandbox.verify.VerifyReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:itest;DB_CLOSE_DELAY=-1",
        "app.seed-on-empty=false"
})
class SandboxServiceIntegrationTest {

    @Autowired SandboxService service;

    private String[] seed() {
        TopologyEntity t = service.saveTopology("test-yard", SampleData.yard());
        RuleSetEntity r = service.createRuleCandidate(rules("v1", true), "safe");
        ScenarioEntity sc = service.saveScenario(null, "safe", SampleData.safeScenario(),
                t.getFingerprint());
        return new String[]{t.getId(), r.getId(), sc.getId()};
    }

    private RuleSet rules(String v, boolean flank) {
        RuleSet r = new RuleSet();
        r.version = v;
        r.requireFlankProtection = flank;
        return r;
    }

    @Test
    void approvedVerificationIsSafe() {
        String[] s = seed();
        service.approve(s[0], s[1], s[2], "tester");
        VerifyReport report = service.verifyApproved(5_000);
        assertEquals(VerifyReport.SAFE, report.conclusion);
    }

    @Test
    void approvalFreezesFingerprintsAndLaterEditsDoNotRewrite() {
        String[] s = seed();
        service.approve(s[0], s[1], s[2], "tester");
        Map<String, Object> before = service.currentApproval();
        // new topology revision must not change frozen pointers
        service.saveTopology("test-yard-v2", SampleData.yard());
        Map<String, Object> after = service.currentApproval();
        assertEquals(before.get("topologyFingerprint"), after.get("topologyFingerprint"));
    }

    @Test
    void staleEditReturnsConflictWithBothPayloads() {
        RuleSetEntity r = service.createRuleCandidate(new RuleSet(), "draft");
        RuleSet incoming = new RuleSet();
        incoming.version = "mine";
        incoming.enforceRouteMutex = false;
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.updateRuleCandidate(r.getId(), 999, incoming));
        Map<String, Object> c = ex.getConflict();
        assertEquals(999L, ((Number) c.get("clientBaseVersion")).longValue());
        assertNotNull(c.get("currentPayload"));
        assertNotNull(c.get("clientPayload"));
    }

    @Test
    void mergeKeepsIndependentEditsFromBothBrowsers() {
        RuleSet base = new RuleSet();
        RuleSetEntity e = service.createRuleCandidate(base, "draft");
        RuleSet server = new RuleSet();
        server.enforceRouteMutex = false;
        service.updateRuleCandidate(e.getId(), e.getLockVersion(), server);

        RuleSet incoming = new RuleSet();
        incoming.requireFlankProtection = false;
        Map<String, Object> merged = service.mergeRuleCandidate(e.getId(), base, incoming);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) merged.get("conflicts");
        assertTrue(conflicts.isEmpty(), "改不同字段不应冲突: " + conflicts);
        @SuppressWarnings("unchecked")
        Map<String, Object> flags = (Map<String, Object>) merged.get("merged");
        assertEquals(false, flags.get("enforceRouteMutex"));
        assertEquals(false, flags.get("requireFlankProtection"));
    }

    @Test
    void sameParameterEditedDifferentlyIsReportedConflict() {
        RuleSet base = new RuleSet();
        base.maxShuntSpeed = 25;
        RuleSetEntity e = service.createRuleCandidate(base, "draft");
        // browser A commits 30
        RuleSet server = new RuleSet();
        server.maxShuntSpeed = 30;
        service.updateRuleCandidate(e.getId(), e.getLockVersion(), server);
        // browser B loaded 25 and submits 40 plus an independent flag edit
        RuleSet incoming = new RuleSet();
        incoming.maxShuntSpeed = 40;
        incoming.releaseOnlyAfterClear = false;
        Map<String, Object> merged = service.mergeRuleCandidate(e.getId(), base, incoming);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) merged.get("conflicts");
        assertTrue(conflicts.stream().anyMatch(c -> "maxShuntSpeed".equals(c.get("flag"))
                        && Integer.valueOf(30).equals(c.get("current"))
                        && Integer.valueOf(40).equals(c.get("yours"))),
                "双方对同一数值参数的不同修改必须完整展示: " + conflicts);
        @SuppressWarnings("unchecked")
        Map<String, Object> flags = (Map<String, Object>) merged.get("merged");
        assertEquals(30, flags.get("maxShuntSpeed"), "冲突优先当前版本");
        assertEquals(false, flags.get("releaseOnlyAfterClear"), "独立改动照常合入");
    }

    @Test
    void faultInsertionCreatesNewScenarioVersion() {
        String[] s = seed();
        ScenarioEntity v2 = service.insertFault(s[2], "SECTION", "S1", false);
        ScenarioEntity v3 = service.insertFault(v2.getId(), "SECTION", "S1", true);
        assertEquals(v2.getId(), v3.getParentVersion());
        assertNotEquals(s[2], v2.getId());
        assertTrue(service.readScenario(v2).failedSections.contains("S1"));
        assertFalse(service.readScenario(v3).failedSections.contains("S1"));
        // original evidence untouched
        assertFalse(service.scenarioStore().findById(s[2]).orElseThrow()
                .getPayloadJson().contains("S1"));
    }

    @Test
    void exportPackageReplaysIdenticalCounterexample() {
        TopologyEntity t = service.saveTopology("yard2", SampleData.yard());
        RuleSet relaxed = new RuleSet();
        relaxed.enforceRouteMutex = false;
        relaxed.requireSwitchPosition = false;
        relaxed.blockSwitchUnderMovement = false;
        RuleSetEntity r = service.createRuleCandidate(relaxed, "relaxed");
        ScenarioEntity sc = service.saveScenario(null, "race", SampleData.conflictScenario(),
                t.getFingerprint());
        Map<String, Object> pkg = service.exportPackage(t.getId(), r.getId(), sc.getId(), 5_000);
        Map<String, Object> replay = service.importAndReplayPackage(pkg);
        assertEquals(true, replay.get("replayMatch"), "跨机器重放必须选中同一条反例: " + replay);
    }

    @Test
    void derivedReportCarriesRuleVersionAndFingerprints() {
        String[] s = seed();
        VerifyReport report = service.verify(s[0], s[1], s[2], 5_000);
        assertNotNull(report.ruleVersion);
        assertNotNull(report.topologyFingerprint);
        assertNotNull(report.scenarioFingerprint);
        assertEquals(64, report.topologyFingerprint.length());
    }

    @Test
    void candidateComparisonShowsReachableStatesAndLengths() {
        String[] s = seed();
        service.approve(s[0], s[1], s[2], "tester");
        RuleSet relaxed = new RuleSet();
        relaxed.version = "relaxed-candidate";
        relaxed.enforceRouteMutex = false;
        relaxed.requireSwitchPosition = false;
        relaxed.blockSwitchUnderMovement = false;
        RuleSetEntity c = service.createRuleCandidate(relaxed, "candidate");
        ScenarioEntity race = service.saveScenario(null, "race",
                SampleData.conflictScenario(),
                service.requireTopology(s[0]).getFingerprint());
        Map<String, Object> cmp = service.compareWithApproved(c.getId(), race.getId(), 5_000);
        assertNotNull(cmp.get("approved"));
        assertNotNull(cmp.get("candidate"));
    }
}
