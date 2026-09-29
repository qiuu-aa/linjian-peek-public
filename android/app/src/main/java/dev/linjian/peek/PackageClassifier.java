package dev.linjian.peek;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** The only package-name classification table used by proactive event filtering. */
public final class PackageClassifier {
    public enum Category { MEANINGFUL, SYSTEM_UI, HELPER_UI, SETTINGS, CHATGPT, SHOPPING_TAKEOUT, TRAVEL_APP }

    private static final Set<String> SYSTEM_UI = set(
            "com.android.systemui", "com.android.launcher", "com.android.launcher2", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher", "com.google.android.as", "com.coloros.systemui",
            "com.oplus.systemui", "com.miui.home", "com.huawei.android.launcher", "com.sec.android.app.launcher",
            "com.android.keyguard", "com.android.dreams.basic", "com.android.dreams.phototable");

    private static final Set<String> HELPER_UI = set(
            "android", "com.android.intentresolver", "com.android.resolver", "com.android.documentsui",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller",
            "com.google.android.packageinstaller", "com.google.android.documentsui", "com.google.android.providers.media.module",
            "com.google.android.photopicker", "com.google.android.apps.photos.picker", "com.android.providers.media",
            "com.android.settings.intelligence", "com.coloros.filemanager", "com.oplus.filemanager",
            "com.coloros.gallery3d", "com.oplus.gallery", "com.miui.gallery", "com.google.android.inputmethod.latin");

    private static final Set<String> SETTINGS = set(
            "com.android.settings", "com.google.android.apps.wellbeing", "com.coloros.digitalwellbeing",
            "com.oplus.digitalwellbeing", "com.miui.securitycenter");

    private static final Set<String> SHOPPING_TAKEOUT = set(
            "com.sankuai.meituan", "me.ele", "com.jingdong.app.mall", "com.taobao.taobao",
            "com.tmall.wireless", "com.xunmeng.pinduoduo", "com.suning.mobile.ebuy", "com.dianping.v1");

    private static final Set<String> TRAVEL_APPS = set(
            "com.mobileticket", "com.autonavi.minimap", "com.baidu.baidumap", "com.tencent.map",
            "com.ctrip.ct", "com.qunar", "com.didapinche.booking");

    private static final Set<String> SHARED_BIKE = set(
            "com.hellobike", "com.mobike.mobikeapp", "com.ofo.labofo", "com.qingqikeji.blackhorse.passenger");

    private static final String CHATGPT = "com.openai.chatgpt";

    private PackageClassifier() { }

    public static Category classify(String packageName, String ownPackage) {
        String pkg = clean(packageName);
        if (pkg.isEmpty()) return Category.HELPER_UI;
        if (CHATGPT.equals(pkg)) return Category.CHATGPT;
        if (SETTINGS.contains(pkg)) return Category.SETTINGS;
        if (SYSTEM_UI.contains(pkg) || pkg.contains("launcher") || pkg.contains("systemui")
                || pkg.contains("keyguard") || pkg.contains("aod") || pkg.contains("fingerprint")) return Category.SYSTEM_UI;
        if (HELPER_UI.contains(pkg) || pkg.contains("inputmethod") || pkg.contains("keyboard")
                || pkg.contains("intentresolver") || pkg.contains("permissioncontroller")
                || pkg.contains("packageinstaller") || pkg.contains("documentsui") || pkg.contains("photopicker")
                || pkg.contains("sharesheet") || pkg.contains("recents")) return Category.HELPER_UI;
        if (!clean(ownPackage).isEmpty() && clean(ownPackage).equals(pkg)) return Category.HELPER_UI;
        if (SHOPPING_TAKEOUT.contains(pkg)) return Category.SHOPPING_TAKEOUT;
        if (TRAVEL_APPS.contains(pkg) || SHARED_BIKE.contains(pkg)) return Category.TRAVEL_APP;
        return Category.MEANINGFUL;
    }

    public static boolean isMeaningful(Category category) {
        return category == Category.MEANINGFUL || category == Category.CHATGPT
                || category == Category.SHOPPING_TAKEOUT || category == Category.TRAVEL_APP;
    }

    public static boolean isSharedBike(String packageName) { return SHARED_BIKE.contains(clean(packageName)); }
    public static boolean isChatGpt(String packageName) { return CHATGPT.equals(clean(packageName)); }
    public static String ignoredRule(Category category) {
        return category == Category.SYSTEM_UI ? "ignored_system_ui" : "ignored_helper_ui";
    }

    private static String clean(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.US); }
    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
