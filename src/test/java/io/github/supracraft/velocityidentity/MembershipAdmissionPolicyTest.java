package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MembershipAdmissionPolicyTest {
    private static final Instant START = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-13T00:00:00Z");
    private static final CanonicalPrincipal HUMAN = new CanonicalPrincipal(
            "https://sessionserver.mojang.com", "uuid-one", PrincipalKind.HUMAN);
    private static final CanonicalPrincipal BOT = new CanonicalPrincipal(
            "urn:supracraft:vip:workload", "gym/alpha/0", PrincipalKind.WORKLOAD);

    private static MembershipAdmissionPolicy policy() {
        return new MembershipAdmissionPolicy(5, START, END,
                Map.of(
                        HUMAN, new MembershipAdmissionPolicy.Grant(
                                Set.of("Two_Rivers"), START, END),
                        BOT, new MembershipAdmissionPolicy.Grant(
                                Set.of("bot-gym"), START, END)),
                Set.of());
    }

    @Test
    void humansAndWorkloadsAreIndependentAndWorldScoped() {
        assertEquals(MembershipAdmissionPolicy.Decision.ALLOW,
                policy().decide(HUMAN, "two_rivers", START));
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                policy().decide(HUMAN, "bot-gym", START));
        assertEquals(MembershipAdmissionPolicy.Decision.ALLOW,
                policy().decide(BOT, "BOT-GYM", START));
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                policy().decide(BOT, "two_rivers", START));
    }

    @Test
    void missingAndGuestLikePrincipalCannotInheritAllow() {
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                policy().decide(null, "two_rivers", START));
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                policy().decide(HUMAN, "unknown", START));
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                policy().decide(HUMAN, "*", START));
    }

    @Test
    void realmAndSubjectCollisionsDoNotAlias() {
        CanonicalPrincipal otherRealm = new CanonicalPrincipal(
                "https://another-idp.invalid", "uuid-one", PrincipalKind.HUMAN);
        CanonicalPrincipal differentKind = new CanonicalPrincipal(
                HUMAN.issuer(), HUMAN.subject(), PrincipalKind.WORKLOAD);
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                policy().decide(otherRealm, "two_rivers", START));
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                policy().decide(differentKind, "two_rivers", START));
    }

    @Test
    void expiresExclusivelyAtBoundary() {
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                policy().decide(HUMAN, "two_rivers", START.minusSeconds(1)));
        assertEquals(MembershipAdmissionPolicy.Decision.ALLOW,
                policy().decide(HUMAN, "two_rivers", END.minusNanos(1)));
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                policy().decide(HUMAN, "two_rivers", END));
    }

    @Test
    void revocationDeniesAndConflictedSnapshotIsRejected() {
        MembershipAdmissionPolicy revoked = new MembershipAdmissionPolicy(
                6, START, END, Map.of(), Set.of(HUMAN));
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                revoked.decide(HUMAN, "two_rivers", START));
        assertThrows(IllegalArgumentException.class,
                () -> new MembershipAdmissionPolicy(7, START, END,
                        Map.of(HUMAN, new MembershipAdmissionPolicy.Grant(
                                Set.of("two_rivers"), START, END)), Set.of(HUMAN)));
    }

    @Test
    void malformedAndImplicitWildcardGrantsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MembershipAdmissionPolicy.Grant(
                        Set.of("*"), START, END));
        assertThrows(IllegalArgumentException.class,
                () -> new MembershipAdmissionPolicy.Grant(
                        Set.of("world name"), START, END));
        assertThrows(IllegalArgumentException.class,
                () -> new MembershipAdmissionPolicy(1, END, START, Map.of(), Set.of()));
    }

    @Test
    void grantCollectionIsDefensivelyCopied() {
        var mutable = new java.util.HashSet<>(Set.of("two_rivers"));
        MembershipAdmissionPolicy.Grant grant =
                new MembershipAdmissionPolicy.Grant(mutable, START, END);
        mutable.clear();
        assertEquals(Set.of("two_rivers"), grant.servers());
        assertThrows(UnsupportedOperationException.class,
                () -> grant.servers().add("other"));
    }

    @Test
    void authorizationIsDeterministicAcrossRepeatedReadback() {
        MembershipAdmissionPolicy policy = policy();
        for (int i = 0; i < 100; i++) {
            assertEquals(MembershipAdmissionPolicy.Decision.ALLOW,
                    policy.decide(BOT, "bot-gym", START.plusSeconds(4)));
            assertEquals(5L, policy.revision());
        }
    }
}
