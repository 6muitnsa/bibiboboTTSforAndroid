package io.bibibobo.tts;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把 bibibobo 包装成系统 TTS 引擎：在系统的「文字转语音输出」里选它作首选引擎，
 * 任何用系统朗读的 App 都能用它；不需要任何常驻服务，合成全在引擎进程内完成。
 *
 * 每个「发音预设」= 一个引擎音色，音色名就是预设在 App 里起的名字。
 */
public class BibiTtsService extends TextToSpeechService {

    public static final String ENGINE = "io.bibibobo.tts";

    private static final String TAG = "BibiTts";
    private static final String LANG = "zho";
    private static final String COUNTRY = "CHN";

    /** 每次合成自增；onStop() 也自增，用来让进行中的合成尽快停下。 */
    private volatile int generation = 0;

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (accepts(lang)) {
            return country != null && !country.isEmpty()
                    ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_AVAILABLE;
        }
        return TextToSpeech.LANG_NOT_SUPPORTED;
    }

    private static boolean accepts(String lang) {
        if (lang == null) return false;
        String l = lang.toLowerCase(Locale.ROOT);
        return l.startsWith("zh") || l.equals("zho") || l.equals("cmn");
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{LANG, COUNTRY, ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    @Override
    protected void onStop() {
        generation++;
    }

    @Override
    protected Set<String> onGetFeaturesForLanguage(String lang, String country, String variant) {
        Set<String> features = new HashSet<>();
        features.add(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS);
        return features;
    }

    private static Voice makeVoice(String name) {
        Set<String> features = new HashSet<>();
        features.add(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS);
        return new Voice(name, Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_HIGH,
                Voice.LATENCY_NORMAL, false, features);
    }

    @Override
    public List<Voice> onGetVoices() {
        List<Voice> voices = new ArrayList<>();
        for (Preset preset : PresetStore.load(this)) {
            voices.add(makeVoice(preset.name));
        }
        return voices;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        if (!accepts(lang)) return null;
        return PresetStore.selected(this).name;
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
        for (Preset preset : PresetStore.load(this)) {
            if (preset.name.equals(voiceName)) return TextToSpeech.SUCCESS;
        }
        return TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String voiceName) {
        return onIsValidVoiceName(voiceName);
    }

    @Override
    protected void onSynthesizeText(final SynthesisRequest request, final SynthesisCallback callback) {
        final int myGeneration = ++generation;
        String text = request.getText();
        if (text == null) text = "";
        if (text.length() > 4000) text = text.substring(0, 4000);

        Preset preset = PresetStore.byVoiceName(this, request.getVoiceName());
        int rate = request.getSpeechRate();
        if (rate <= 0) rate = 100;
        double speed = Math.max(0.3, Math.min(rate / 100.0, 3.0));
        final double step = preset.step / speed;

        int max = callback.getMaxBufferSize();
        final int chunk = Math.max(2048, max & ~1);

        int rc = callback.start(BibiSynth.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1);
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "start() 被拒绝 rc=" + rc);
            return;
        }
        if (text.trim().isEmpty()) {
            callback.done();
            return;
        }
        try {
            final byte[] pcm = new byte[chunk];
            final boolean[] stopped = new boolean[]{false};
            BibiSynth.stream(text, preset.voice(), step, preset.gap, preset.transpose,
                    new BibiSynth.PcmSink() {
                        @Override
                        public void onSamples(float[] samples, int length) {
                            if (stopped[0] || myGeneration != generation) return;
                            int offset = 0;
                            while (offset < length) {
                                int count = Math.min(chunk / 2, length - offset);
                                BibiSynth.toPcm16(samples, offset, count, pcm, 0);
                                int result = callback.audioAvailable(pcm, 0, count * 2);
                                if (result != TextToSpeech.SUCCESS) {
                                    stopped[0] = true;
                                    return;
                                }
                                offset += count;
                            }
                        }
                    });
            if (myGeneration != generation) {
                Log.d(TAG, "合成被中止");
                return;
            }
            callback.done();
            Log.d(TAG, "合成完成 " + text.length() + " 字 预设=" + preset.name + " speed=" + speed);
        } catch (Throwable error) {
            Log.e(TAG, "合成失败", error);
            callback.error();
        }
    }
}
