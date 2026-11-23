package io.bibibobo.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * bibibobo 合成器的 Java 移植（与 bibibobo.js 一一对应）。
 *
 * 一条锯齿波 -> 两个带通滤波器捏口型 -> 一个字一声（按字哈希选音高和口型）
 * -> 每个角色一组音阶 -> 标点管语气 -> tanh 软限幅。
 */
public final class BibiSynth {

    public static final int SAMPLE_RATE = 44100;

    private static final double TAU = Math.PI * 2;

    /** 五种口型：每种只是两个共振峰频率（Hz）。 */
    private static final int[][] VOWELS = {
            {800, 1200}, {400, 2200}, {350, 800}, {600, 1700}, {300, 2600}
    };

    /** 一个汉字算一声，一串英文或数字算一声。 */
    private static final Pattern SYLLABLE = Pattern.compile("[A-Za-z_/.0-9]+|[\u4e00-\u9fff]");

    /** 按标点切成句子，每句各自套一条语气规则。 */
    private static final Pattern SENTENCE = Pattern.compile(
            "[^？?！!。.，,、；;…\n]+[？?！!。.，,、；;…]*");

    private static final Pattern SHOUT = Pattern.compile("[！!]");

    public interface PcmSink {
        void onSamples(float[] samples, int length);
    }

    /** 声线：音阶（MIDI 音高）、共振峰倍率、每个字的时长上限、字头滑音、默认语速。 */
    public static final class Voice {
        public final String id;
        public final int[] scale;
        public final double formant;
        public final double maxDur;
        public final double hold;
        public final double chirp;
        public final double fall;
        public final double gain;
        public final double step;

        private Voice(String id, int[] scale, double formant, double maxDur, double hold,
                      double chirp, double fall, double gain, double step) {
            this.id = id;
            this.scale = scale;
            this.formant = formant;
            this.maxDur = maxDur;
            this.hold = hold;
            this.chirp = chirp;
            this.fall = fall;
            this.gain = gain;
            this.step = step;
        }
    }

    public static final Voice FISH = new Voice(
            "fish", new int[]{76, 79, 81, 83, 84}, 1.25, 0.13, 0.8, 1.5, 0, 0.16, 0.13);
    public static final Voice LUMO = new Voice(
            "lumo", new int[]{62, 64, 67, 69}, 0.9, 0.20, 0.7, 0, -2, 0.17, 0.16);

    private BibiSynth() {
    }

    public static Voice byName(String name) {
        if (name == null) return FISH;
        String n = name.toLowerCase();
        if (n.contains("lumo") || n.contains("路墨")) return LUMO;
        return FISH;
    }

    /** MIDI 音高 -> 频率。 */
    private static double mtof(double m) {
        return 440.0 * Math.pow(2.0, (m - 69.0) / 12.0);
    }

    /** 同一个字永远得到同一个数。 */
    public static int hash(String s) {
        int h = 7;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            h = (h * 31 + cp) % 9973;
            i += Character.charCount(cp);
        }
        return h;
    }

    public static List<String> syllables(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = SYLLABLE.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static final class Sentence {
        final String text;
        final List<String> tokens;

        Sentence(String text) {
            this.text = text;
            this.tokens = syllables(text);
        }
    }

    private static List<Sentence> sentences(String text) {
        List<Sentence> out = new ArrayList<>();
        Matcher m = SENTENCE.matcher(text);
        while (m.find()) {
            Sentence s = new Sentence(m.group());
            if (!s.tokens.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 抗混叠锯齿波（polyBLEP）。 */
    private static final class Saw {
        private double ph = 0;
        private final double sr;

        Saw(double sr) {
            this.sr = sr;
        }

        double next(double f) {
            double dt = f / sr;
            ph += dt;
            if (ph >= 1) ph -= 1;
            double v = 2 * ph - 1;
            if (ph < dt) {
                double t = ph / dt;
                v -= t + t - t * t - 1;
            } else if (ph > 1 - dt) {
                double t = (ph - 1) / dt;
                v -= t * t + t + t + 1;
            }
            return v;
        }
    }

    /** 状态变量滤波器，取带通输出。 */
    private static final class Bandpass {
        private double lp = 0;
        private double bp = 0;
        private final double sr;

        Bandpass(double sr) {
            this.sr = sr;
        }

        double process(double x, double fc, double q) {
            double f = 2 * Math.sin((Math.PI * Math.min(fc, sr / 6)) / sr);
            double hp = x - lp - bp / q;
            bp += f * hp;
            lp += f * bp;
            return bp;
        }
    }

    /** 把一声写进 out：音高 m，时长 dur 秒，glide 是结尾滑多少个半音。 */
    private static void voice(float[] out, double t, double m, double dur, Voice v,
                              double glide, int vowel) {
        Saw osc = new Saw(SAMPLE_RATE);
        Bandpass f1 = new Bandpass(SAMPLE_RATE);
        Bandpass f2 = new Bandpass(SAMPLE_RATE);
        double a = VOWELS[vowel % VOWELS.length][0];
        double b = VOWELS[vowel % VOWELS.length][1];
        int start = (int) Math.floor(t * SAMPLE_RATE);
        int n = (int) Math.floor(dur * SAMPLE_RATE);
        for (int i = 0; i < n && start + i < out.length; i++) {
            double x = i / (double) SAMPLE_RATE;
            double pitch = mtof(m + glide * (x / dur) + v.chirp * Math.exp(-x * 30))
                    * (1 + 0.012 * Math.sin(TAU * 6 * x));
            double s = osc.next(pitch);
            double env = Math.min(1, x * 200) * Math.min(1, (dur - x) * 40);
            out[start + i] = (float) (out[start + i]
                    + (f1.process(s, a * v.formant, 4) + 0.7 * f2.process(s, b * v.formant, 5))
                    * env * v.gain);
        }
    }

    /**
     * 逐句合成并回调，内存只需容纳一句。
     *
     * @param step 每个字的间隔（秒），调用方按语速折算好再传进来
     */
    public static void stream(String text, Voice v, double step, double gap, double transpose, PcmSink sink) {
        if (text == null || text.isEmpty()) return;
        if (step <= 0) step = v.step;
        if (gap < 0) gap = 0;
        boolean first = true;
        for (Sentence s : sentences(text)) {
            double lead = first ? 0.02 : 0;
            first = false;
            double total = lead + s.tokens.size() * step + gap;
            float[] out = new float[(int) Math.ceil(total * SAMPLE_RATE)];
            boolean ask = endsWithAny(s.text, "？", "?");
            boolean shout = SHOUT.matcher(s.text).find();
            boolean sad = endsWithAny(s.text, "……", "...", "…");
            double t = lead;
            int n = s.tokens.size();
            for (int i = 0; i < n; i++) {
                String w = s.tokens.get(i);
                boolean last = i == n - 1;
                double m = v.scale[hash(w) % v.scale.length] + (shout ? 3 : 0) - (sad ? 3 : 0) + transpose;
                double dur = Math.min(step * v.hold, v.maxDur) + (last && (ask || shout || sad) ? 0.1 : 0);
                double glide = last && ask ? 5 : last && sad ? -4 : last ? v.fall : 0;
                voice(out, t + i * step, m, dur, v, glide, hash(w));
            }
            for (int i = 0; i < out.length; i++) out[i] = (float) Math.tanh(out[i] * 2.4);
            sink.onSamples(out, out.length);
        }
    }

    private static boolean endsWithAny(String s, String... suffixes) {
        for (String suffix : suffixes) {
            if (s.endsWith(suffix)) return true;
        }
        return false;
    }

    /** 把 float 采样打包成 16 位单声道 PCM。 */
    public static int toPcm16(float[] samples, int sampleOffset, int length, byte[] out, int outOffset) {
        for (int i = 0; i < length; i++) {
            double x = Math.max(-1, Math.min(1, samples[sampleOffset + i]));
            int v = (int) Math.round(x * 32767);
            out[outOffset + i * 2] = (byte) (v & 0xff);
            out[outOffset + i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return length * 2;
    }

    /** 便捷方法：整段合成为 PCM（测试用）。 */
    public static byte[] toPcm(String text, Voice v, double step, double gap, double transpose) {
        final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        stream(text, v, step, gap, transpose, new PcmSink() {
            @Override
            public void onSamples(float[] samples, int length) {
                byte[] buf = new byte[length * 2];
                toPcm16(samples, 0, length, buf, 0);
                bos.write(buf, 0, buf.length);
            }
        });
        return bos.toByteArray();
    }
}
