package com.railway.sandbox.config;

import com.railway.sandbox.model.RuleSet;
import com.railway.sandbox.model.Scenario;
import com.railway.sandbox.model.Topology;
import com.railway.sandbox.repo.RuleSetEntity;
import com.railway.sandbox.repo.ScenarioRepository;
import com.railway.sandbox.repo.TopologyRepository;
import com.railway.sandbox.service.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Seed a demonstration yard on first boot so the UI is immediately useful. */
@Configuration
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final SandboxService service;
    private final TopologyRepository topologies;
    private final ScenarioRepository scenarios;

    public DataSeeder(SandboxService service, TopologyRepository topologies,
                      ScenarioRepository scenarios) {
        this.service = service;
        this.topologies = topologies;
        this.scenarios = scenarios;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (topologies.count() > 0) {
            log.info("已有拓扑数据，跳过种子");
            return;
        }
        log.info("播种演示车场拓扑/规则/场景");
        Topology t = SampleData.yard();
        var top = service.saveTopology("演示车场（一渡两股）", t);

        RuleSet rules = new RuleSet();
        rules.version = "v1-safe";
        rules.note = "完整互锁规则（批准版）";
        RuleSetEntity approved = service.createRuleCandidate(rules, "完整互锁规则");

        var safeSc = service.saveScenario(null, "单线接车（安全）",
                SampleData.safeScenario(), top.getFingerprint());
        service.saveScenario("lineage-demo", "双车竞争进路（含反例场景）",
                SampleData.conflictScenario(), top.getFingerprint());

        service.approve(top.getId(), approved.getId(), safeSc.getId(), "seeder");

        RuleSet relaxed = new RuleSet();
        relaxed.version = "v2-relaxed";
        relaxed.note = "候选：关闭侧向防护与释放顺序";
        relaxed.requireFlankProtection = false;
        relaxed.enforceReleaseOrder = false;
        service.createRuleCandidate(relaxed, "演示被弱化的候选规则");
        log.info("种子完成: topology={} rules={} scenario={}",
                top.getId(), approved.getId(), safeSc.getId());
    }
}
