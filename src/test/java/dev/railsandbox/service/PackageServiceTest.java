package dev.railsandbox.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import dev.railsandbox.verify.VerificationModels;

import static org.junit.jupiter.api.Assertions.*;

class PackageServiceTest {
    @Test
    void exportedPackageRecomputesSameDeterministicCounterexample() throws Exception {
        PackageService service = new PackageService(null);
        byte[] archive = service.exportPackageDirect(
                SampleData.topology(),
                SampleData.approvedRules(),
                SampleData.weakFlankRules(),
                SampleData.scenario(),
                64,
                50_000
        );
        Map<String, Object> result = service.importPackage(archive);

        assertEquals(true, result.get("verified"));
        VerificationModels.VerificationReport candidate =
                (VerificationModels.VerificationReport) result.get("candidate");
        assertEquals(VerificationModels.VerificationConclusion.COUNTEREXAMPLE, candidate.conclusion());
        assertEquals(2, candidate.counterexampleLength());
    }
}
