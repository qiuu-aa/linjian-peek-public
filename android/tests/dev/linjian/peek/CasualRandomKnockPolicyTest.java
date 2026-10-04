package dev.linjian.peek;

import java.time.ZonedDateTime;
import java.util.Random;

public final class CasualRandomKnockPolicyTest {
    private static long at(String clock) { return ZonedDateTime.parse("2026-10-04T" + clock + ":00+08:00[Asia/Shanghai]").toInstant().toEpochMilli(); }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    private static CasualRandomKnockPolicy plan(int count, long now) {
        return CasualRandomKnockPolicy.ensure(null, now, true, count, 0, new Random(23));
    }
    private static CasualRandomKnockPolicy.Decision decide(CasualRandomKnockPolicy p,
            CasualRandomKnockPolicy.Target t, long now, boolean on, boolean screen, boolean app, boolean stable, boolean network) {
        return p.evaluate(t, now, on, screen, app, stable, network, 0);
    }
    public static void main(String[] args) {
        for (int count = 1; count <= 3; count++) for (int seed = 0; seed < 500; seed++) {
            CasualRandomKnockPolicy p = CasualRandomKnockPolicy.ensure(null, at("08:00"), true, count, 0, new Random(seed));
            check(p.requested == count, "configured count");
            check(p.targets.size() == count, "daily count 1 to 3");
            for (CasualRandomKnockPolicy.Target t : p.targets) check(CasualRandomKnockPolicy.available(t.at), "safe interval");
            for (int i = 1; i < p.targets.size(); i++) check(p.targets.get(i).at - p.targets.get(i - 1).at >= CasualRandomKnockPolicy.GAP, "two-hour separation");
        }
        check(!CasualRandomKnockPolicy.available(at("12:00")), "lunch excluded");
        check(!CasualRandomKnockPolicy.available(at("18:00")), "dinner excluded");
        check(!CasualRandomKnockPolicy.available(at("23:45")), "night excluded");
        CasualRandomKnockPolicy p = plan(1, at("08:00"));
        String encoded = p.encode();
        CasualRandomKnockPolicy restored = CasualRandomKnockPolicy.ensure(CasualRandomKnockPolicy.decode(encoded), at("09:00"), true, 1, 0, new Random(999));
        check(encoded.equals(restored.encode()), "restart preserves targets");
        CasualRandomKnockPolicy.Target t = p.targets.get(0);
        check(decide(p,t,t.at,true,false,true,true,true) == CasualRandomKnockPolicy.Decision.DEFER, "screen off");
        for (String pkg : new String[]{"com.openai.chatgpt", "com.android.launcher3", "dev.linjian.peek", "com.android.settings", "com.android.systemui", "com.google.android.inputmethod.latin"}) {
            PackageClassifier.Category c = PackageClassifier.classify(pkg, "dev.linjian.peek");
            boolean safe = PackageClassifier.isMeaningful(c) && !PackageClassifier.isChatGpt(pkg);
            check(decide(p,t,t.at,true,true,safe,true,true) == CasualRandomKnockPolicy.Decision.DEFER, "unsafe app " + pkg);
        }
        check(decide(p,t,t.at,true,true,true,false,true) == CasualRandomKnockPolicy.Decision.DEFER, "transient switch");
        check(decide(p,t,t.at,true,true,true,true,false) == CasualRandomKnockPolicy.Decision.DEFER, "offline");
        check(decide(p,t,t.at,true,true,true,true,true) == CasualRandomKnockPolicy.Decision.FIRE, "valid activity in grace");
        long resumed = t.at + 10 * CasualRandomKnockPolicy.MINUTE;
        // Advance to an available minute within grace when a target sits just before lunch.
        if (!CasualRandomKnockPolicy.available(resumed)) resumed = t.at;
        check(decide(p,t,resumed,true,true,true,true,true) == CasualRandomKnockPolicy.Decision.FIRE, "next foreground resumes");
        check(decide(p,t,t.at + CasualRandomKnockPolicy.GRACE + 1,true,true,true,true,true) == CasualRandomKnockPolicy.Decision.ABANDON, "90-minute expiry after network restore");
        check(decide(p,t,t.at,false,true,true,true,true) == CasualRandomKnockPolicy.Decision.DONE, "disabled");
        check(p.claim(t,t.at), "claim once");
        check(!p.claim(t,t.at), "cannot claim twice");
        CasualRandomKnockPolicy consumed = CasualRandomKnockPolicy.decode(p.encode());
        check(!consumed.claim(consumed.targets.get(0),t.at), "restart cannot replay claimed target");
        check(decide(p,t,t.at,true,true,true,true,true) == CasualRandomKnockPolicy.Decision.DONE, "already consumed");
        CasualRandomKnockPolicy replan = CasualRandomKnockPolicy.replan(p,t.at,true,3,0,new Random(4));
        check(replan.targets.get(0).id.equals(t.id) && replan.targets.get(0).actual == t.actual, "count change retains consumed target");
        check(replan.firedCount() == 1 && replan.plannedCount() <= 3, "only remaining targets rearranged");
        CasualRandomKnockPolicy reduced = CasualRandomKnockPolicy.replan(replan,t.at,true,1,0,new Random(7));
        check(reduced.firedCount() == 1 && reduced.targets.size() == 1, "reduce quota preserves fired and removes pending");
        replan.cancelPending();
        check(replan.targets.stream().noneMatch(x -> "pending".equals(x.status)), "off cancels remaining");
        CasualRandomKnockPolicy enabled = CasualRandomKnockPolicy.replan(replan,at("20:00"),true,3,0,new Random(5));
        check(enabled.targets.stream().filter(x -> "pending".equals(x.status)).allMatch(x -> x.at > at("20:00")), "enable never catches up");
        CasualRandomKnockPolicy late = plan(3,at("21:00"));
        check(late.targets.size() == 1, "limited remaining day gives one");
        check(plan(1,at("22:30")).targets.isEmpty(), "no nighttime target");
        CasualRandomKnockPolicy edge = plan(1,at("21:00"));
        check(decide(edge,edge.targets.get(0),at("22:30"),true,true,true,true,true)
                == CasualRandomKnockPolicy.Decision.ABANDON, "grace cannot enter late hours");
        CasualRandomKnockPolicy cooldown = CasualRandomKnockPolicy.ensure(null,at("14:00"),true,3,at("14:00"),new Random(2));
        check(cooldown.targets.stream().allMatch(x -> x.at >= at("16:00")), "important event planning exclusion");
        CasualRandomKnockPolicy.Target ct = cooldown.targets.get(0);
        check(cooldown.evaluate(ct,ct.at,true,true,true,true,true,ct.at - 30 * CasualRandomKnockPolicy.MINUTE)
                == CasualRandomKnockPolicy.Decision.DEFER, "new important event defers existing target");
        long tomorrow = at("08:00") + 24 * 60 * CasualRandomKnockPolicy.MINUTE;
        CasualRandomKnockPolicy next = CasualRandomKnockPolicy.ensure(p,tomorrow,true,1,0,new Random(6));
        check(next.day != p.day && next.firedCount() == 0 && !next.targets.get(0).id.equals(t.id), "new Shanghai date new plan");
        System.out.println("CasualRandomKnockPolicyTest: all scenarios passed (1500 randomized days)");
    }
}
