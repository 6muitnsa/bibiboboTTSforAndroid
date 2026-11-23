package io.bibibobo.tts;

/** 一条发音预设：声线 + 语速 + 移调 + 句间停顿。 */
public class Preset {

    public String id;
    public String name;
    /** "fish"（大肥鱼）或 "lumo"（路墨）。 */
    public String base;
    /** 每个字的间隔，秒。 */
    public double step;
    /** 整体移调，半音。 */
    public double transpose;
    /** 句子之间停多久，秒。 */
    public double gap;

    public Preset(String id, String name, String base, double step, double transpose, double gap) {
        this.id = id;
        this.name = name;
        this.base = base;
        this.step = step;
        this.transpose = transpose;
        this.gap = gap;
    }

    public BibiSynth.Voice voice() {
        return "lumo".equals(base) ? BibiSynth.LUMO : BibiSynth.FISH;
    }

    public String summary() {
        String voice = "lumo".equals(base) ? "路墨" : "大肥鱼";
        String shift = transpose == 0 ? "原调" : String.format(java.util.Locale.ROOT, "%+.0f 半音", transpose);
        return String.format(java.util.Locale.ROOT,
                "%s · 每字 %.2fs · %s · 停顿 %.2fs", voice, step, shift, gap);
    }
}
