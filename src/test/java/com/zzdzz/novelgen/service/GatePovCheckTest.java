package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.service.GateService.GateCheck;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人称一致性保守判据（povCheck 纯函数）：只抓整章级错配。
 * 关键口径：先剥对白只看叙述层——对白里的「我」不算第一人称证据，
 * 叙述层无「我」= 配置第一人称却整章漂成第三人称（书 59 第 3 章那类）。
 */
class GatePovCheckTest {

    /** 第一人称正常章：叙述层大量「我」（叙述 >100 字过样本量闸）。 */
    private static final String FIRST_OK = """
            我推开门，走廊的灯一盏接一盏亮起来。舱壁上的航线图还亮着，标记停在环带外侧。
            我数着风机转动的间隔，等他把话说完。掌心出了汗，金属扣有些滑。
            我摇头。他没答。他知道我为什么签那三年，他不打算说。
            我把箱子换手，三年前拎它进来，今天拎它出去，一样轻。箱壳上留着几道旧划痕。
            我没回头。舰门在身后合拢，把那个名字关在里面。走廊的风从领口灌进来，凉得贴骨。
            我笑了一声，对他说今天清了，互不相欠。他终于朝我走了半步，只有半步。
            """;

    /** 配置第一人称但整章第三人称叙述（书 59 ch3 形态）：叙述主语全是人物名/她，「我」只在对白里。 */
    private static final String DRIFTED_TO_THIRD = """
            陆青禾站在泊位内线，身后两名护卫，肩甲上的陆家徽记在暗处泛着冷光。
            她摘下面罩，舱盖弹开。周牧跨出来，一只手扶着舱沿。
            「小小姐。」他笑，「路上堵。」
            她没接话。视线从他发红的眼白挪到领口，再落到他扶舱盖的那只手上。
            「你喝酒了。」她说。
            「没有。」周牧把领口拍了拍，「外头沙子迷的。」
            她转身上艇，跨进舱门前停了一步，回头。
            「先去见他们。」她说，「酒醒之前，你不许开口！」
            周牧跟上去，爬上驾驶位，闭嘴。艇身离地，贴着泊舱通道往外滑。
            """;

    /** 第三人称正常章：叙述层几乎无「我」。 */
    private static final String THIRD_OK = """
            周牧把艇拐进一条侧道。侧道尽头只有一道闸口在往上抬。
            郗临靠回椅背，闭眼。他这一年问过七回同一个问题。
            周牧没再劝。劝过两回，第三回起就只当没看见。
            他摸出烟盒，又塞回去。指节在盒盖上敲了两下，到底没点。
            闸口的封板落下来。头顶的灯管闪了两下，彻底黑了。
            """;

    /** 配置第三人称但叙述通篇「我」（反向漂移）。 */
    private static final String DRIFTED_TO_FIRST = """
            我推开门的时候，周牧正在擦枪。他抬头看我，又低下头去。
            我把账册拍在桌上，问他这半年账上进了多少。他说一分没进。
            我不信。我翻开账册，一页一页看过去，末尾一排缺口，墨迹也淡了。
            我合上册子，抬眼看一圈。十几张脸都绷着，没人敢先开口。
            我说，明天起，函上不用他们的章。周牧抬眼，说行会只认章。
            我说，陆家认我。满屋安静，没人站起来，也没有人先开口。
            """;

    @Test
    void firstPersonOkPasses() {
        GateCheck c = GateService.povCheck("第一人称（主角）", FIRST_OK);
        assertThat(c).isNotNull();
        assertThat(c.check()).isEqualTo("pov_consistent");
        assertThat(c.ok()).isTrue();
        assertThat(((Number) c.value()).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void firstPersonConfiguredButThirdPersonNarrationFails() {
        // 书 59 ch3 形态：「我」只在对白里，剥掉对白后叙述层 0 次 → 错配
        GateCheck c = GateService.povCheck("第一人称（主角）", DRIFTED_TO_THIRD);
        assertThat(c).isNotNull();
        assertThat(c.ok()).isFalse();
        assertThat(c.value()).isEqualTo(0);
    }

    @Test
    void thirdPersonOkPasses() {
        GateCheck c = GateService.povCheck("第三人称限知", THIRD_OK);
        assertThat(c).isNotNull();
        assertThat(c.ok()).isTrue();
    }

    @Test
    void thirdPersonConfiguredButFirstPersonNarrationFails() {
        GateCheck c = GateService.povCheck("第三人称限知", DRIFTED_TO_FIRST);
        assertThat(c).isNotNull();
        assertThat(c.ok()).isFalse();
        assertThat(((Number) c.value()).doubleValue()).isGreaterThanOrEqualTo(30.0);
    }

    @Test
    void dialogueOnlyFirstPersonDoesNotCountAsEvidence() {
        // 「我」只出现在对白里、叙述层全第三人称 → 剥对白后叙述层 0 次「我」→ 判错配
        GateCheck c = GateService.povCheck("第一人称", DRIFTED_TO_THIRD);
        assertThat(c).isNotNull();
        assertThat(c.ok()).isFalse();
    }

    @Test
    void multiViewpointAndUnknownAndMissingSkipCheck() {
        // 多视角轮换：逐章由章纲定，机械判据不越权
        assertThat(GateService.povCheck("多视角轮换", FIRST_OK)).isNull();
        // 空/空白期望：未配置不查
        assertThat(GateService.povCheck(null, FIRST_OK)).isNull();
        assertThat(GateService.povCheck("  ", FIRST_OK)).isNull();
        // 全知视角：不判（允许自由进出人物内心，判人称只会误报）
        assertThat(GateService.povCheck("第三人称全知", FIRST_OK)).isNull();
        assertThat(GateService.povCheck("全知视角", FIRST_OK)).isNull();
        // 空正文
        assertThat(GateService.povCheck("第一人称", null)).isNull();
        assertThat(GateService.povCheck("第一人称", "")).isNull();
    }

    @Test
    void thinNarrationSkipsCheck() {
        // 叙述层样本不足（剥对白后 <100 汉字）→ 不判（fail-open，宁漏不误）
        String dialogueHeavy = "「我来了我走了我吃饭了我睡觉了我出门了我回家了我开心我难过我生气我无奈」\n"
                .repeat(10);
        assertThat(GateService.povCheck("第一人称", dialogueHeavy)).isNull();
    }

    @Test
    void stripDialogueRemovesAllThreeQuoteStyles() {
        String text = "叙述层有我。「对白里也有我」“还有这种我也”“和直引号我”结尾。";
        String stripped = GateService.stripDialogue(text);
        assertThat(stripped).doesNotContain("对白");
        assertThat(stripped).doesNotContain("“");
        assertThat(stripped).doesNotContain("\"");
        assertThat(stripped).contains("叙述层有我");
    }
}
