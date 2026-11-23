package io.bibibobo.tts;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 预设保存在 SharedPreferences 里，引擎和界面读同一份。 */
public final class PresetStore {

    private static final String PREFS = "bibibobo";
    private static final String KEY_PRESETS = "presets";
    private static final String KEY_SELECTED = "selected";

    private PresetStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Preset> load(Context context) {
        String raw = prefs(context).getString(KEY_PRESETS, null);
        List<Preset> presets = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    presets.add(new Preset(
                            o.optString("id"),
                            o.optString("name"),
                            o.optString("base", "fish"),
                            o.optDouble("step", 0.13),
                            o.optDouble("transpose", 0),
                            o.optDouble("gap", 0.22)));
                }
            } catch (Exception ignored) {
                presets.clear();
            }
        }
        if (presets.isEmpty()) {
            presets.add(new Preset("fish", "大肥鱼", "fish", 0.13, 0, 0.22));
            presets.add(new Preset("lumo", "路墨", "lumo", 0.16, 0, 0.22));
            save(context, presets);
        }
        return presets;
    }

    public static synchronized void save(Context context, List<Preset> presets) {
        JSONArray array = new JSONArray();
        for (Preset p : presets) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("base", p.base);
                o.put("step", p.step);
                o.put("transpose", p.transpose);
                o.put("gap", p.gap);
            } catch (Exception ignored) {
            }
            array.put(o);
        }
        prefs(context).edit().putString(KEY_PRESETS, array.toString()).apply();
    }

    public static String selectedId(Context context) {
        return prefs(context).getString(KEY_SELECTED, "fish");
    }

    public static void setSelectedId(Context context, String id) {
        prefs(context).edit().putString(KEY_SELECTED, id).apply();
    }

    public static Preset selected(Context context) {
        List<Preset> presets = load(context);
        String id = selectedId(context);
        for (Preset p : presets) {
            if (p.id.equals(id)) return p;
        }
        return presets.get(0);
    }

    public static Preset byVoiceName(Context context, String voiceName) {
        List<Preset> presets = load(context);
        if (voiceName != null && !voiceName.isEmpty()) {
            for (Preset p : presets) {
                if (p.name.equals(voiceName)) return p;
            }
        }
        return selected(context);
    }
}
