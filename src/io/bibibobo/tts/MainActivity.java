package io.bibibobo.tts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 哔哔啵啵的界面：试听 + 发音预设管理。
 *
 * 预设就是 TTS 引擎的音色：引擎报给系统的音色名，就是这里这几条预设的名字。
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFFF1F7F4;
    private static final int CARD = 0xFFFFFFFF;
    private static final int INK = 0xFF12211B;
    private static final int MUTED = 0xFF64786F;
    private static final int ACCENT = 0xFF12A57A;
    private static final int ACCENT_SOFT = 0xFFE1F5EC;
    private static final int HEADER = 0xFF0B241E;
    private static final int MINT = 0xFF7CF5C6;
    private static final int LINE = 0xFFE0EBE5;

    private List<Preset> presets;
    private String selectedId;

    private AudioTrack track;
    private boolean editing;

    private TextView globalStatus;
    private TextView globalButton;
    private boolean promptChecked;

    /** 系统默认朗读引擎的 Secure 设置键（App 只能读，改要用户在设置里点）。 */
    private static final String KEY_DEFAULT_SYNTH = "tts_default_synth";
    private static final String KEY_PROMPTED = "prompted_global";

    private final Runnable stopAudio = new Runnable() {
        @Override
        public void run() {
            stopPreview();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        presets = PresetStore.load(this);
        selectedId = PresetStore.selectedId(this);
        showMain();
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!editing) refreshGlobalStatus();
        if (!promptChecked) {
            promptChecked = true;
            maybePromptGlobal();
        }
    }

    @Override
    public void onBackPressed() {
        if (editing) {
            showMain();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- 主界面

    private void showMain() {
        editing = false;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);

        page.addView(header("哔哔啵啵", "把一句话变成小人说话声 · 离线合成，不需要服务"));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(28));
        page.addView(body);

        // 试听卡片
        LinearLayout preview = card();
        preview.addView(label("试听", INK, 15, true));
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText("今天吃白饭？没饭了！真的没饭了……");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTextColor(INK);
        input.setMinLines(2);
        input.setBackground(round(0xFFF6FAF8, 10, LINE, 1));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        preview.addView(input, matchWrap());
        final TextView previewStatus = label("", MUTED, 12, false);
        previewStatus.setPadding(0, dp(8), 0, 0);
        View playButton = primary("用当前预设试听", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Preset p = selectedPreset();
                String text = input.getText().toString();
                if (text.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "先写一句话", Toast.LENGTH_SHORT).show();
                    return;
                }
                previewStatus.setText("正在合成「" + p.name + "」……");
                play(p, text, previewStatus);
            }
        });
        preview.addView(playButton, matchWrapTop(dp(10)));
        preview.addView(previewStatus, matchWrap());
        body.addView(preview, matchWrapTop(dp(12)));

        // 全局朗读引擎
        LinearLayout global = card();
        global.addView(label("全局朗读引擎", INK, 15, true));
        globalStatus = label("", MUTED, 12, false);
        globalStatus.setPadding(0, dp(4), 0, dp(10));
        globalStatus.setLineSpacing(dp(3), 1f);
        global.addView(globalStatus);
        globalButton = (TextView) ghost("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isGlobalEngine()) {
                    openTtsSettings();
                } else {
                    showGlobalDialog();
                }
            }
        });
        global.addView(globalButton, matchWrap());
        body.addView(global, matchWrapTop(dp(12)));

        // 预设列表
        TextView listTitle = label("发音预设", INK, 17, true);
        listTitle.setPadding(dp(4), dp(22), 0, dp(6));
        body.addView(listTitle);
        TextView listHint = label("点一条设为当前预设。它同时是引擎的音色名。", MUTED, 12, false);
        listHint.setPadding(dp(4), 0, 0, dp(10));
        body.addView(listHint);

        for (final Preset preset : presets) {
            body.addView(presetRow(preset), matchWrapTop(dp(8)));
        }

        View create = ghost("＋ 新建预设", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditor(new Preset(String.valueOf(System.currentTimeMillis()),
                        "新预设 " + (presets.size() - 1), "fish", 0.13, 0, 0.22), true);
            }
        });
        body.addView(create, matchWrapTop(dp(14)));

        // 用法提示
        LinearLayout usage = card();
        usage.addView(label("怎么用", INK, 15, true));
        TextView steps = label(
                "· 在系统的「文字转语音输出」里把首选引擎设为「哔哔啵啵」，"
                        + "所有用系统朗读的 App 都会用它。\n"
                        + "· 支持单独指定朗读引擎的 App，也可以在它自己的设置里选本引擎。\n"
                        + "· 每个发音预设都是引擎的一个音色；预设改名后，别处绑定的旧音色需要重新选一次。\n\n"
                        + "路径：设置 → 更多设置 → 无障碍 → 文字转语音输出 → 首选引擎",
                MUTED, 13, false);
        steps.setLineSpacing(dp(4), 1f);
        steps.setPadding(0, dp(8), 0, 0);
        usage.addView(steps);
        View check = ghost("检查引擎与音色", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selfCheck();
            }
        });
        usage.addView(check, matchWrapTop(dp(12)));
        body.addView(usage, matchWrapTop(dp(22)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
    }

    private Preset selectedPreset() {
        for (Preset p : presets) {
            if (p.id.equals(selectedId)) return p;
        }
        return presets.get(0);
    }

    private View presetRow(final Preset preset) {
        boolean current = preset.id.equals(selectedId);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(12), dp(14));
        row.setBackground(round(current ? ACCENT_SOFT : CARD, 14,
                current ? ACCENT : LINE, current ? 2 : 1));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(preset.name, INK, 16, true);
        TextView summary = label(preset.summary(), MUTED, 12, false);
        summary.setPadding(0, dp(3), 0, 0);
        texts.addView(name);
        texts.addView(summary);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (current) {
            TextView badge = label("使用中", ACCENT, 12, true);
            badge.setPadding(dp(8), dp(4), dp(8), dp(4));
            badge.setBackground(round(0xFFFFFFFF, 20, ACCENT, 1));
            row.addView(badge);
        }

        TextView edit = label("编辑", ACCENT, 14, true);
        edit.setPadding(dp(14), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editLp.leftMargin = dp(6);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditor(copyOf(preset), false);
            }
        });
        row.addView(edit, editLp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedId = preset.id;
                PresetStore.setSelectedId(MainActivity.this, selectedId);
                showMain();
            }
        });
        return row;
    }

    // ---------------------------------------------------------------- 全局引擎

    private boolean isGlobalEngine() {
        String current = Settings.Secure.getString(getContentResolver(), KEY_DEFAULT_SYNTH);
        return BibiTtsService.ENGINE.equals(current);
    }

    /** 当前默认引擎的显示名：能取到 App 就用 App 名，否则用包名。 */
    private String globalEngineName() {
        String current = Settings.Secure.getString(getContentResolver(), KEY_DEFAULT_SYNTH);
        if (current == null || current.isEmpty()) return "未设置（跟随系统）";
        try {
            android.content.pm.ApplicationInfo info =
                    getPackageManager().getApplicationInfo(current, 0);
            return getPackageManager().getApplicationLabel(info).toString();
        } catch (Exception ignored) {
            return current;
        }
    }

    private void refreshGlobalStatus() {
        if (globalStatus == null || globalButton == null) return;
        if (isGlobalEngine()) {
            globalStatus.setText("当前：哔哔啵啵（已是系统默认朗读引擎）");
            globalButton.setText("打开系统设置");
        } else {
            globalStatus.setText("当前：" + globalEngineName()
                    + "\n系统不允许 App 自己改默认引擎，需要在设置里点一下。");
            globalButton.setText("设为全局朗读引擎");
        }
    }

    private void maybePromptGlobal() {
        if (isGlobalEngine()) return;
        android.content.SharedPreferences prefs =
                getSharedPreferences("bibibobo", MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PROMPTED, false)) return;
        prefs.edit().putBoolean(KEY_PROMPTED, true).apply();
        showGlobalDialog();
    }

    private void showGlobalDialog() {
        new AlertDialog.Builder(this)
                .setTitle("把哔哔啵啵设为全局朗读引擎？")
                .setMessage("设为默认后，所有用系统朗读的 App 都会用它。\n\n"
                        + "有些 App 支持单独指定朗读引擎，那样也可以，不必改全局。\n\n"
                        + "路径：设置 → 更多设置 → 无障碍 → 文字转语音输出 → 首选引擎")
                .setPositiveButton("打开设置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        openTtsSettings();
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    /**
     * 打开系统的文字转语音设置。
     *
     * 没有公开的 Intent，按「TTS 设置页 → 无障碍设置 → 系统设置」依次尝试，
     * 前一个在当前系统上不存在时抛异常就换下一个。
     */
    private void openTtsSettings() {
        Intent tts = new Intent("com.android.settings.TTS_SETTINGS");
        Intent accessibility = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        Intent settings = new Intent(Settings.ACTION_SETTINGS);
        for (Intent intent : new Intent[]{tts, accessibility, settings}) {
            try {
                startActivity(intent);
                Toast.makeText(this,
                        "在「文字转语音输出 → 首选引擎」里选「哔哔啵啵」",
                        Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {
                // 换下一个
            }
        }
        Toast.makeText(this, "打不开设置，请手动去 设置 → 无障碍 → 文字转语音输出",
                Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- 编辑页

    private void showEditor(final Preset draft, final boolean isNew) {
        editing = true;
        final String originalId = draft.id;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.addView(editorHeader(isNew ? "新建预设" : "编辑预设"));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(28));
        page.addView(body);

        LinearLayout form = card();
        form.addView(label("名称", MUTED, 12, false));
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(draft.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setTextColor(INK);
        name.setBackground(round(0xFFF6FAF8, 10, LINE, 1));
        name.setPadding(dp(12), dp(10), dp(12), dp(10));
        form.addView(name, matchWrapTop(dp(6)));

        // 声线
        TextView voiceLabel = label("声线", MUTED, 12, false);
        voiceLabel.setPadding(0, dp(20), 0, dp(6));
        form.addView(voiceLabel);
        final String[] base = new String[]{draft.base};
        final TextView fishBtn = new TextView(this);
        final TextView lumoBtn = new TextView(this);
        fishBtn.setText("大肥鱼（高）");
        lumoBtn.setText("路墨（低）");
        LinearLayout voiceRow = new LinearLayout(this);
        voiceRow.setOrientation(LinearLayout.HORIZONTAL);
        styleChoice(fishBtn, "fish".equals(base[0]));
        styleChoice(lumoBtn, "lumo".equals(base[0]));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        voiceRow.addView(fishBtn, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        half2.leftMargin = dp(8);
        voiceRow.addView(lumoBtn, half2);
        form.addView(voiceRow, matchWrapTop(dp(2)));
        fishBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                base[0] = "fish";
                styleChoice(fishBtn, true);
                styleChoice(lumoBtn, false);
            }
        });
        lumoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                base[0] = "lumo";
                styleChoice(fishBtn, false);
                styleChoice(lumoBtn, true);
            }
        });

        // 三个滑杆
        final double[] step = new double[]{draft.step};
        final double[] transpose = new double[]{draft.transpose};
        final double[] gap = new double[]{draft.gap};

        final TextView stepValue = label("", ACCENT, 13, true);
        SeekBar stepBar = seekBar(22, (int) Math.round((draft.step - 0.08) / 0.01));
        stepBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                step[0] = 0.08 + progress * 0.01;
                stepValue.setText(String.format(Locale.ROOT, "每字 %.2f 秒 · %.2f×",
                        step[0], 0.13 / step[0]));
            }
        });
        stepValue.setText(String.format(Locale.ROOT, "每字 %.2f 秒 · %.2f×",
                step[0], 0.13 / step[0]));
        addSlider(form, "语速", stepValue, stepBar, "越小越快");

        final TextView transposeValue = label("", ACCENT, 13, true);
        SeekBar transposeBar = seekBar(12, (int) Math.round(draft.transpose + 6));
        transposeBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                transpose[0] = progress - 6;
                transposeValue.setText(transpose[0] == 0 ? "原调"
                        : String.format(Locale.ROOT, "%+.0f 半音", transpose[0]));
            }
        });
        transposeValue.setText(transpose[0] == 0 ? "原调"
                : String.format(Locale.ROOT, "%+.0f 半音", transpose[0]));
        addSlider(form, "音高", transposeValue, transposeBar, "整体移调");

        final TextView gapValue = label("", ACCENT, 13, true);
        SeekBar gapBar = seekBar(55, (int) Math.round((draft.gap - 0.05) / 0.01));
        gapBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gap[0] = 0.05 + progress * 0.01;
                gapValue.setText(String.format(Locale.ROOT, "%.2f 秒", gap[0]));
            }
        });
        gapValue.setText(String.format(Locale.ROOT, "%.2f 秒", gap[0]));
        addSlider(form, "句间停顿", gapValue, gapBar, "标点切句之间");

        body.addView(form, matchWrap());

        final TextView editorStatus = label("", MUTED, 12, false);
        editorStatus.setPadding(dp(4), dp(10), dp(4), 0);
        body.addView(editorStatus);

        View previewButton = ghost("试听这个预设", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Preset p = new Preset(draft.id, name.getText().toString(),
                        base[0], step[0], transpose[0], gap[0]);
                editorStatus.setText("正在合成「" + p.name + "」……");
                play(p, "今天吃白饭？没饭了！真的没饭了……", editorStatus);
            }
        });
        body.addView(previewButton, matchWrapTop(dp(16)));

        View save = primary("保存", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String newName = name.getText().toString().trim();
                if (newName.isEmpty()) {
                    editorStatus.setText("名字不能为空");
                    return;
                }
                for (Preset other : presets) {
                    if (!other.id.equals(originalId) && other.name.equals(newName)) {
                        editorStatus.setText("已经有同名预设了");
                        return;
                    }
                }
                Preset target = new Preset(originalId, newName, base[0], step[0], transpose[0], gap[0]);
                boolean replaced = false;
                for (int i = 0; i < presets.size(); i++) {
                    if (presets.get(i).id.equals(originalId)) {
                        presets.set(i, target);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) presets.add(target);
                PresetStore.save(MainActivity.this, presets);
                if (isNew) {
                    selectedId = target.id;
                    PresetStore.setSelectedId(MainActivity.this, selectedId);
                } else if (selectedId.equals(target.id)) {
                    // 名称变了，旧 id 不变，选择不受影响
                }
                showMain();
            }
        });
        body.addView(save, matchWrapTop(dp(22)));

        if (!isNew && presets.size() > 1) {
            View delete = ghost("删除这个预设", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int i = 0; i < presets.size(); i++) {
                        if (presets.get(i).id.equals(originalId)) {
                            presets.remove(i);
                            break;
                        }
                    }
                    PresetStore.save(MainActivity.this, presets);
                    if (selectedId.equals(originalId)) {
                        selectedId = presets.get(0).id;
                        PresetStore.setSelectedId(MainActivity.this, selectedId);
                    }
                    showMain();
                }
            });
            body.addView(delete, matchWrapTop(dp(10)));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
    }

    /** 用系统 TTS 客户端反查本引擎：Android 到底认到了几个引擎、几个音色。 */
    private void selfCheck() {
        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(final int status) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        StringBuilder text = new StringBuilder();
                        TextToSpeech tts = holder[0];
                        try {
                            if (status != TextToSpeech.SUCCESS) {
                                text.append("引擎初始化失败：status=").append(status);
                            } else {
                                java.util.Set<Voice> voices = tts.getVoices();
                                java.util.List<TextToSpeech.EngineInfo> engines = tts.getEngines();
                                text.append("系统里的 TTS 引擎：\n");
                                for (TextToSpeech.EngineInfo info : engines) {
                                    text.append("· ").append(info.label)
                                            .append("  (").append(info.name).append(")\n");
                                }
                                text.append("\n本引擎报出的音色：")
                                        .append(voices == null ? "null" : voices.size()).append(" 个\n");
                                if (voices != null) {
                                    for (Voice voice : voices) {
                                        text.append("· ").append(voice.getName())
                                                .append("  [").append(voice.getLocale().toLanguageTag()).append("]\n");
                                    }
                                }
                                if (voices == null || voices.isEmpty()) {
                                    text.append("\n没有音色说明注册异常，请把这段截图发给开发者。");
                                }
                            }
                        } catch (Exception error) {
                            text.append("查询失败：").append(error);
                        } finally {
                            if (tts != null) tts.shutdown();
                        }
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("引擎自检")
                                .setMessage(text.toString())
                                .setPositiveButton("好", null)
                                .show();
                    }
                });
            }
        }, BibiTtsService.ENGINE);
    }

    // ---------------------------------------------------------------- 播放

    private void play(Preset preset, String text, final TextView status) {
        stopPreview();
        if (text.length() > 300) text = text.substring(0, 300);
        byte[] pcm = BibiSynth.toPcm(text, preset.voice(), preset.step, preset.gap, preset.transpose);
        int minBuffer = AudioTrack.getMinBufferSize(BibiSynth.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuffer, pcm.length);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(BibiSynth.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufferSize)
                .build();
        track.write(pcm, 0, pcm.length);
        track.play();
        status.setText(String.format(Locale.ROOT, "「%s」· %.1f 秒", preset.name,
                pcm.length / 2.0 / BibiSynth.SAMPLE_RATE));
    }

    private void stopPreview() {
        if (track != null) {
            try {
                track.stop();
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
    }

    // ---------------------------------------------------------------- 视图小工具

    private View header(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(HEADER);
        box.setPadding(dp(20), dp(28), dp(20), dp(22));
        TextView t = label(title, MINT, 26, true);
        box.addView(t);
        TextView s = label(subtitle, 0xFFA7C4B8, 13, false);
        s.setPadding(0, dp(6), 0, 0);
        box.addView(s);
        return box;
    }

    private View editorHeader(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundColor(HEADER);
        box.setPadding(dp(12), dp(18), dp(16), dp(18));
        TextView back = label("← 返回", MINT, 15, true);
        back.setPadding(dp(4), dp(6), dp(12), dp(6));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMain();
            }
        });
        box.addView(back);
        box.addView(label(title, 0xFFFFFFFF, 18, true));
        return box;
    }

    private void addSlider(LinearLayout parent, String title, TextView value, SeekBar bar, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(title, INK, 14, true);
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(value);
        box.addView(top);
        box.addView(bar, matchWrap());
        TextView hintView = label(hint, MUTED, 11, false);
        box.addView(hintView);
        parent.addView(box);
    }

    private void styleChoice(TextView view, boolean selected) {
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setPadding(0, dp(11), 0, dp(11));
        view.setTextColor(selected ? 0xFFFFFFFF : INK);
        view.setBackground(round(selected ? ACCENT : 0xFFF6FAF8, 10,
                selected ? ACCENT : LINE, 1));
    }

    private SeekBar seekBar(int max, int progress) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(Math.max(1, max));
        bar.setProgress(Math.max(0, Math.min(progress, max)));
        return bar;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackground(round(CARD, 16, LINE, 1));
        return box;
    }

    private TextView label(String text, int color, float sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private View primary(String text, View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setPadding(dp(18), dp(14), dp(18), dp(14));
        view.setBackground(round(ACCENT, 12, 0, 0));
        view.setOnClickListener(listener);
        return view;
    }

    private View ghost(String text, View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(ACCENT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setPadding(dp(18), dp(13), dp(18), dp(13));
        view.setBackground(round(0xFFFFFFFF, 12, ACCENT, 1));
        view.setOnClickListener(listener);
        return view;
    }

    private Drawable round(int color, float radiusDp, int strokeColor, float strokeWidthDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0) shape.setStroke(dp(strokeWidthDp), strokeColor);
        return shape;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapTop(int top) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = top;
        return lp;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Preset copyOf(Preset p) {
        return new Preset(p.id, p.name, p.base, p.step, p.transpose, p.gap);
    }

    /** 只订阅 onProgressChanged 的 SeekBar 适配器。 */
    private static abstract class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
