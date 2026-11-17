package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

final class FacialExpressions {
    record Control(int index, String name, String group, String label) {}

    static List<Control> controls(NativeFunc api, long model, int count) {
        var indices = new HashMap<String, Integer>();
        for (int i = 0; i < count; i++) indices.putIfAbsent(api.GetMorphName(model, i), i);
        var result = new ArrayList<Control>();
        add(result, indices, "ExpressionMouth",
            "あ", "张嘴（A）", "あ２", "张嘴（A 2）", "あ３", "张嘴（A 3）",
            "い", "咧嘴（I）", "い２", "咧嘴（I 2）", "い３", "咧嘴（I 3）",
            "う", "拢嘴（U）", "う２", "拢嘴（U 2）", "え", "张嘴（E）", "え２", "张嘴（E 2）", "え３", "张嘴（E 3）",
            "お", "圆嘴（O）", "お２", "圆嘴（O 2）", "口横広げ", "嘴巴加宽",
            "口横縮げ", "嘴巴收窄", "口横缩げ", "嘴巴收窄（另一版）", "口角上げ", "嘴角上扬", "口角下げ", "嘴角下压",
            "口角下げ右", "右嘴角下压", "口角下げ左", "左嘴角下压", "口角下げ２", "嘴角下压（2）",
            "ぺろっ", "吐舌", "歯無し", "隐藏牙齿", "歯無し上", "隐藏上牙", "歯無し下", "隐藏下牙",
            "吻", "亲吻", "嘟嘴", "嘟嘴", "∞", "波浪嘴", "∞ ", "波浪嘴（2）");
        add(result, indices, "ExpressionEyes",
            "まばたき", "闭眼", "まばたき２", "闭眼（2）", "びっくり", "睁大眼", "びっくり右", "睁大右眼", "びっくり左", "睁大左眼",
            "笑い", "笑眼", "ウィンク", "左眼眨眼", "ウィンク右", "右眼眨眼",
            "ウィンク２", "左眼眨眼（2）", "ウィンク２右", "右眼眨眼（2）", "ウィンク３", "左眼眨眼（3）", "ウィンク３右", "右眼眨眼（3）",
            "瞳大", "放大瞳孔", "瞳大右", "放大右瞳孔", "瞳大左", "放大左瞳孔", "瞳小", "缩小瞳孔", "瞳小右", "缩小右瞳孔", "瞳小左", "缩小左瞳孔",
            "じと目", "半眯眼", "なごみ", "眯眼微笑", "はぅ", "紧闭双眼", "はちゅ目", "蜥蜴眼", "星目", "星星眼", "ぐるぐる", "圈圈眼",
            "はぁと", "爱心眼", "滑稽", "滑稽眼", "光消", "去掉眼睛高光", "恐ろしい子！", "惊愕眼", "赤目", "红眼");
        add(result, indices, "ExpressionBrows",
            "上", "抬眉", "上右", "抬右眉", "上左", "抬左眉", "下", "压眉", "下右", "压右眉", "下左", "压左眉",
            "困る", "八字眉", "困る右", "右八字眉", "困る左", "左八字眉", "怒り", "怒眉", "怒り右", "右怒眉", "怒り左", "左怒眉",
            "真面目", "认真眉", "ｷﾘｯ", "锐利眉");
        add(result, indices, "ExpressionOther",
            "照れ", "脸红", "涙", "眼泪", "汗", "汗滴", "怒", "生气",
            "Special_Frown", "皱眉表情", "Special_Grievance", "委屈表情", "Special_Grin", "咧嘴笑表情", "Special_Suprise", "惊讶表情");
        return result;
    }

    private static void add(List<Control> result, HashMap<String, Integer> indices, String group, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            Integer index = indices.get(pairs[i]);
            if (index != null) result.add(new Control(index, pairs[i], group, pairs[i + 1]));
        }
    }
}
