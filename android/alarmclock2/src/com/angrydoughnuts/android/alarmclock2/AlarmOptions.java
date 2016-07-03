/****************************************************************************
 * Copyright 2016 kraigs.android@gmail.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ****************************************************************************/

package com.angrydoughnuts.android.alarmclock2;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Calendar;

public class AlarmOptions extends DialogFragment {
  private static final Uri default_settings = ContentUris.withAppendedId(
      AlarmClockProvider.SETTINGS_URI,
      AlarmNotificationService.DEFAULTS_ALARM_ID);

  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
    super.onCreateDialog(savedInstanceState);

    {
      String p = Manifest.permission.READ_EXTERNAL_STORAGE;
      if (getContext().checkPermission(p, Process.myPid(), Process.myUid()) !=
          PackageManager.PERMISSION_GRANTED)
        requestPermissions(new String[] { p }, 0);
    }

    final long id = getArguments().getLong(
        AlarmNotificationService.ALARM_ID, -1);
    final boolean defaults = id == AlarmNotificationService.DEFAULTS_ALARM_ID;

    final OptionsViews views = new OptionsViews(
        getContext(), getFragmentManager(), id);
    final BaseAdapter adapter = defaults ?
      new DefaultOptionsAdapter(views) :
      new StandardOptionsAdapter(views);
    final ListView v = new ListView(getContext());
    v.setAdapter(adapter);

    if (savedInstanceState != null) {
      TimePicker t = (TimePicker)getFragmentManager()
        .findFragmentByTag("edit_alarm");
      RepeatEditor r = (RepeatEditor)getFragmentManager()
        .findFragmentByTag("edit_repeat");
      MediaPicker m = (MediaPicker)getFragmentManager()
        .findFragmentByTag("edit_tone");
      if (t != null) t.setListener(views.time_listener);
      if (r != null) r.setListener(views.repeat_listener);
      if (m != null) m.setListener(views.tone_listener);
    }

    return new AlertDialog.Builder(getContext())
      .setTitle(defaults ? "Default Alarm Options" : "Alarm Options")
      .setView(v)
      .setPositiveButton("Done", null)
      .setNeutralButton(!defaults ? "Delete" : null,
        new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            new DialogFragment() {
              @Override
              public Dialog onCreateDialog(Bundle savedInstanceState) {
                return new AlertDialog.Builder(getContext())
                  .setTitle("Confirm Delete")
                  .setMessage("Are you sure you want to delete this alarm?")
                  .setNegativeButton("Cancel", null)
                  .setPositiveButton(
                      "OK", new DialogInterface.OnClickListener() {
                      @Override
                      public void onClick(DialogInterface dialog, int which) {
                        getContext().getContentResolver().delete(
                            ContentUris.withAppendedId(
                                AlarmClockProvider.ALARMS_URI, id), null, null);
                        AlarmNotificationService.removeAlarmTrigger(
                            getContext(), id);
                      }
                  }).create();
              }
            }.show(getFragmentManager(), "confirm_delete");
          }
        }).create();
  }

  static public class RepeatEditor extends DialogFragment {
    final public static String BITMASK = "bitmask";
    public static interface OnPickListener {
      abstract void onPick(int repeats);
    }

    private OnPickListener listener = null;
    public void setListener(OnPickListener l) { listener = l; }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
      super.onCreateDialog(savedInstanceState);

      final boolean checked[] = new boolean[] {
        false, false,false,false,false,false,false
      };
      if (getArguments() != null && savedInstanceState == null) {
        int b = getArguments().getInt(BITMASK, 0);
        for (int i = 0; i < 7; ++i)
          checked[i] = (b & (1 << i)) != 0;
      }
      final CharSequence days[] = new CharSequence[] {
        "Sunday",
        "Monday",
        "Tuesday",
        "Wednesday",
        "Thursday",
        "Friday",
        "Saturday"
      };
      return new AlertDialog.Builder(getContext())
        .setTitle("Repeat")
        .setMultiChoiceItems(days, checked, null)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                  if (listener == null)
                    return;
                  int b = 0;
                  ListView list = ((AlertDialog)dialog).getListView();
                  for (int i = 0; i < list.getCount(); ++i)
                    if (list.isItemChecked(i))
                      b |= 1 << i;
                  listener.onPick(b);
                }
              })
        .create();
    }
  }

  static public class AlarmSettings {
    public final int time;
    public final boolean enabled;
    public final String label;
    public final int repeat;
    public final long next_snooze;

    static public AlarmSettings get(Context context, long id) {
      AlarmSettings s = null;
      final Cursor c = context.getContentResolver().query(
          ContentUris.withAppendedId(AlarmClockProvider.ALARMS_URI, id),
          new String[] {
            AlarmClockProvider.AlarmEntry.TIME,
            AlarmClockProvider.AlarmEntry.ENABLED,
            AlarmClockProvider.AlarmEntry.NAME,
            AlarmClockProvider.AlarmEntry.DAY_OF_WEEK,
            AlarmClockProvider.AlarmEntry.NEXT_SNOOZE },
          null, null, null);
      if (c.moveToFirst())
        s = new AlarmSettings(c);
      else
        s = new AlarmSettings();
      c.close();
      return s;
    }

    public static int getTime(Context context, long id) {
      Cursor c = context.getContentResolver().query(
          ContentUris.withAppendedId(AlarmClockProvider.ALARMS_URI, id),
          new String[] { AlarmClockProvider.AlarmEntry.TIME },
          null, null, null);
      c.moveToFirst();
      int time = c.getInt(c.getColumnIndex(AlarmClockProvider.AlarmEntry.TIME));
      c.close();
      return time;
    }

    public static String getLabel(Context context, long id) {
      Cursor c = context.getContentResolver().query(
          ContentUris.withAppendedId(AlarmClockProvider.ALARMS_URI, id),
          new String[] { AlarmClockProvider.AlarmEntry.NAME },
          null, null, null);
      c.moveToFirst();
      String label = c.getString(c.getColumnIndex(
          AlarmClockProvider.AlarmEntry.NAME));
      c.close();
      return label;
    }

    public static int getRepeat(Context context, long id) {
      Cursor c = context.getContentResolver().query(
          ContentUris.withAppendedId(AlarmClockProvider.ALARMS_URI, id),
          new String[] { AlarmClockProvider.AlarmEntry.DAY_OF_WEEK },
          null, null, null);
      c.moveToFirst();
      int repeat = c.getInt(c.getColumnIndex(
          AlarmClockProvider.AlarmEntry.DAY_OF_WEEK));
      c.close();
      return repeat;
    }

    public static long getNextSnooze(Context context, long id) {
      Cursor c = context.getContentResolver().query(
          ContentUris.withAppendedId(AlarmClockProvider.ALARMS_URI, id),
          new String[] { AlarmClockProvider.AlarmEntry.NEXT_SNOOZE },
          null, null, null);
      c.moveToFirst();
      long next_snooze = c.getInt(c.getColumnIndex(
          AlarmClockProvider.AlarmEntry.NEXT_SNOOZE));
      c.close();
      return next_snooze;
    }

    private AlarmSettings() {
      time = 0;
      enabled = false;
      label = "Not found";
      repeat = 0;
      next_snooze = 0;
    }

    private AlarmSettings(Cursor c) {
      time = c.getInt(c.getColumnIndex(AlarmClockProvider.AlarmEntry.TIME));
      enabled = c.getInt(c.getColumnIndex(
          AlarmClockProvider.AlarmEntry.ENABLED)) != 0;
      label = c.getString(c.getColumnIndex(AlarmClockProvider.AlarmEntry.NAME));
      repeat = c.getInt(c.getColumnIndex(
          AlarmClockProvider.AlarmEntry.DAY_OF_WEEK));
      next_snooze = c.getLong(c.getColumnIndex(
          AlarmClockProvider.AlarmEntry.NEXT_SNOOZE));
    }
  }

  static public class OptionalSettings {
    public final Uri tone_url;
    public final String tone_name;
    public final int snooze;
    public final boolean vibrate;
    public final int volume_starting;
    public final int volume_ending;
    public final int volume_time;

    static public final Uri TONE_URL_DEFAULT =
      Settings.System.DEFAULT_NOTIFICATION_URI;
    static public final String TONE_NAME_DEFAULT = "System default";
    static public final int SNOOZE_DEFAULT = 10;
    static public final boolean VIBRATE_DEFAULT = false;
    static public final int VOLUME_STARTING_DEFAULT = 0;
    static public final int VOLUME_ENDING_DEFAULT = 100;
    static public final int VOLUME_TIME_DEFAULT = 20;

    public static OptionalSettings get(Context context, long id) {
      OptionalSettings s = null;
      Cursor c = query(context, id);
      if (c.moveToFirst())
        s = new OptionalSettings(c);
      c.close();

      if (s == null) {
        c = query(context, AlarmNotificationService.DEFAULTS_ALARM_ID);
        if (c.moveToFirst())
          s = new OptionalSettings(c);
        c.close();
      }

      if (s != null)
        return s;
      else
        return new OptionalSettings();
    }

    static private Cursor query(Context context, long id) {
      return context.getContentResolver().query(
          ContentUris.withAppendedId(AlarmClockProvider.SETTINGS_URI, id),
          new String[] {
            AlarmClockProvider.SettingsEntry.TONE_URL,
            AlarmClockProvider.SettingsEntry.TONE_NAME,
            AlarmClockProvider.SettingsEntry.SNOOZE,
            AlarmClockProvider.SettingsEntry.VIBRATE,
            AlarmClockProvider.SettingsEntry.VOLUME_STARTING,
            AlarmClockProvider.SettingsEntry.VOLUME_ENDING,
            AlarmClockProvider.SettingsEntry.VOLUME_TIME },
          null, null, null);
    }

    private OptionalSettings() {
      tone_url = TONE_URL_DEFAULT;
      tone_name = TONE_NAME_DEFAULT;
      snooze = SNOOZE_DEFAULT;
      vibrate = VIBRATE_DEFAULT;
      volume_starting = VOLUME_STARTING_DEFAULT;
      volume_ending = VOLUME_ENDING_DEFAULT;
      volume_time = VOLUME_TIME_DEFAULT;
    }

    private OptionalSettings(Cursor c) {
      tone_url = Uri.parse(c.getString(c.getColumnIndex(
          AlarmClockProvider.SettingsEntry.TONE_URL)));
      tone_name = c.getString(c.getColumnIndex(
          AlarmClockProvider.SettingsEntry.TONE_NAME));
      snooze = c.getInt(c.getColumnIndex(
          AlarmClockProvider.SettingsEntry.SNOOZE));
      vibrate = c.getInt(c.getColumnIndex(
          AlarmClockProvider.SettingsEntry.VIBRATE)) != 0;
      volume_starting = c.getInt(c.getColumnIndex(
          AlarmClockProvider.SettingsEntry.VOLUME_STARTING));
      volume_ending = c.getInt(c.getColumnIndex(
          AlarmClockProvider.SettingsEntry.VOLUME_ENDING));
      volume_time = c.getInt(c.getColumnIndex(
          AlarmClockProvider.SettingsEntry.VOLUME_TIME));
    }
  }

  private static class StandardOptionsAdapter extends BaseAdapter {
    private enum Views {
      EDIT_TIME,
      EDIT_REPEAT,
      EDIT_LABEL,
      EDIT_TONE,
      EDIT_SNOOZE,
      EDIT_VIBRATE,
      EDIT_VOLUME,
    }

    private final OptionsViews v;
    public StandardOptionsAdapter(OptionsViews views) { v = views; }

    @Override
    public long getItemId(int position) { return position; }
    @Override
    public int getItemViewType(int position) { return position; }
    @Override
    public int getCount() { return Views.values().length; }
    @Override
    public int getViewTypeCount() { return Views.values().length; }
    @Override
    public Object getItem(int position) { return null; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      if (convertView != null)
        return convertView;

      switch(Views.values()[position]) {
        case EDIT_TIME: return v.edit_time;
        case EDIT_REPEAT: return v.edit_repeat;
        case EDIT_LABEL: return v.edit_label;
        case EDIT_TONE: return v.edit_tone;
        case EDIT_SNOOZE: return v.snooze_layout;
        case EDIT_VIBRATE: return v.edit_vibrate;
        case EDIT_VOLUME: return v.volume_layout;
      }
      return null;
    }
  }

  private static class DefaultOptionsAdapter extends BaseAdapter {
    private enum Views {
      EDIT_TONE,
      EDIT_SNOOZE,
      EDIT_VIBRATE,
      EDIT_VOLUME,
    }

    private final OptionsViews v;
    public DefaultOptionsAdapter(OptionsViews views) { v = views; }

    @Override
    public long getItemId(int position) { return position; }
    @Override
    public int getItemViewType(int position) { return position; }
    @Override
    public int getCount() { return Views.values().length; }
    @Override
    public int getViewTypeCount() { return Views.values().length; }
    @Override
    public Object getItem(int position) { return null; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      if (convertView != null)
        return convertView;

      switch(Views.values()[position]) {
        case EDIT_TONE: return v.edit_tone;
        case EDIT_SNOOZE: return v.snooze_layout;
        case EDIT_VIBRATE: return v.edit_vibrate;
        case EDIT_VOLUME: return v.volume_layout;
      }
      return null;
    }
  }

  private static class OptionsViews {
    public final Button edit_time;
    public final TimePicker.OnTimePickListener time_listener;
    public final Button edit_repeat;
    public final RepeatEditor.OnPickListener repeat_listener;
    public final EditText edit_label;
    public final Button edit_tone;
    public final MediaPicker.Listener tone_listener;
    public final LinearLayout snooze_layout;
    public final Button edit_vibrate;
    public final LinearLayout volume_layout;

    public OptionsViews(
        final Context c, final FragmentManager fm, final long id) {
      final Uri uri = ContentUris.withAppendedId(
          AlarmClockProvider.ALARMS_URI, id);
      final Uri settings = ContentUris.withAppendedId(
          AlarmClockProvider.SETTINGS_URI, id);

      final AlarmSettings alarm = AlarmSettings.get(c, id);
      final OptionalSettings s = OptionalSettings.get(c, id);

      edit_time = new Button(c);
      time_listener = new TimePicker.OnTimePickListener() {
          @Override
          public void onTimePick(int t) {
            ContentValues val = new ContentValues();
            val.put(AlarmClockProvider.AlarmEntry.TIME, t);
            c.getContentResolver().update(uri, val, null, null);

            final Calendar next = TimeUtil.nextOccurrence(
                t, AlarmSettings.getRepeat(c, id),
                AlarmSettings.getNextSnooze(c, id));
            if (alarm.enabled) {
              AlarmNotificationService.removeAlarmTrigger(c, id);
              AlarmNotificationService.scheduleAlarmTrigger(
                  c, id, next.getTimeInMillis());
            }

            edit_time.setText(TimeUtil.formatLong(c, next));
          }
        };
      edit_time.setText(TimeUtil.formatLong(c, TimeUtil.nextOccurrence(alarm.time, alarm.repeat)));
      edit_time.setOnClickListener(
          new View.OnClickListener() {
            @Override
            public void onClick(View view) {
              int time = AlarmSettings.getTime(c, id);
              int repeats = AlarmSettings.getRepeat(c, id);
              TimePicker time_pick = new TimePicker();
              time_pick.setListener(time_listener);
              Bundle b = new Bundle();
              b.putInt(TimePicker.TIME, time);
              b.putString(TimePicker.TITLE, "Edit time");
              b.putInt(TimePicker.REPEATS, repeats);
              time_pick.setArguments(b);
              time_pick.show(fm, "edit_alarm");
            }
          });

      edit_repeat = new Button(c);
      repeat_listener = new RepeatEditor.OnPickListener() {
          @Override
          public void onPick(int repeats) {
            ContentValues val = new ContentValues();
            val.put(AlarmClockProvider.AlarmEntry.DAY_OF_WEEK, repeats);
            c.getContentResolver().update(uri, val, null, null);
            edit_repeat.setText("" + repeats);
            final Calendar next = TimeUtil.nextOccurrence(
                AlarmSettings.getTime(c, id), repeats,
                AlarmSettings.getNextSnooze(c, id));
            if (alarm.enabled) {
              AlarmNotificationService.removeAlarmTrigger(c, id);
              AlarmNotificationService.scheduleAlarmTrigger(
                  c, id, next.getTimeInMillis());
            }
          }
        };
      edit_repeat.setText("" + alarm.repeat);
      edit_repeat.setOnClickListener(
          new View.OnClickListener() {
            @Override
            public void onClick(View view) {
              int repeat = AlarmSettings.getRepeat(c, id);
              RepeatEditor edit = new RepeatEditor();
              Bundle b = new Bundle();
              b.putInt(RepeatEditor.BITMASK, repeat);
              edit.setArguments(b);
              edit.setListener(repeat_listener);
              edit.show(fm, "edit_repeat");
            }
          });

      // TODO: this doesn't show the keyboard if there isn't another edit view
      // from the xml layout...
      edit_label = new EditText(c);
      edit_label.setText(alarm.label);
      edit_label.setSelection(alarm.label.length());
      edit_label.setHint("Label");
      edit_label.setSingleLine(true);
      edit_label.addTextChangedListener(new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
          @Override
          public void onTextChanged(CharSequence s, int st, int b, int c) {}
          @Override
          public void afterTextChanged(Editable s) {
            final String name = s.toString();
            ContentValues val = new ContentValues();
            val.put(AlarmClockProvider.AlarmEntry.NAME, name);
            c.getContentResolver().update(uri, val, null, null);
          }
        });

      edit_tone = new Button(c);
      tone_listener = new MediaPicker.Listener() {
          public void onMediaPick(Uri uri, String title) {
            ContentValues val = new ContentValues();
            val.put(AlarmClockProvider.SettingsEntry.TONE_URL, uri.toString());
            val.put(AlarmClockProvider.SettingsEntry.TONE_NAME, title);
            if (c.getContentResolver().update(settings, val, null, null) < 1)
              c.getContentResolver().insert(settings, val);

            edit_tone.setText(title + " " + uri.toString());
          }
        };
      edit_tone.setText(s.tone_name + " " + s.tone_url.toString());
      edit_tone.setOnClickListener(
          new View.OnClickListener() {
            @Override
            public void onClick(View view) {
              MediaPicker media_pick = new MediaPicker();
              media_pick.setListener(tone_listener);
              media_pick.show(fm, "edit_tone");
            }
          });

      final TextView snooze_status = new TextView(c);
      snooze_status.setText("Snooze: " + s.snooze);
      final SeekBar edit_snooze = new SeekBar(c);
      edit_snooze.setMax(11);
      edit_snooze.setProgress((s.snooze - 5) / 5);
      edit_snooze.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean user) {
              final int snooze = progress * 5 + 5;
              snooze_status.setText("Snooze: " + snooze);
            }
            @Override
            public void onStartTrackingTouch(SeekBar s) {}
            @Override
            public void onStopTrackingTouch(SeekBar s) {
              final int snooze = s.getProgress() * 5 + 5;
              ContentValues val = new ContentValues();
              val.put(AlarmClockProvider.SettingsEntry.SNOOZE, snooze);
              if (c.getContentResolver().update(settings, val, null, null) < 1)
                c.getContentResolver().insert(settings, val);
            }
          });
      snooze_layout = new LinearLayout(c);
      snooze_layout.setOrientation(LinearLayout.VERTICAL);
      snooze_layout.addView(snooze_status);
      snooze_layout.addView(edit_snooze);

      edit_vibrate = new Button(c);
      edit_vibrate.setText("vibrate " + s.vibrate);
      edit_vibrate.setOnClickListener(
          new View.OnClickListener() {
            @Override
            public void onClick(View view) {
              // TODO change this to a toggle button and use the toggle state
              // rather than a db lookup.
              boolean vibrate = !OptionalSettings.get(c, id).vibrate;
              ContentValues val = new ContentValues();
              val.put(AlarmClockProvider.SettingsEntry.VIBRATE, vibrate);
              if (c.getContentResolver().update(settings, val, null, null) < 1)
                c.getContentResolver().insert(settings, val);
              edit_vibrate.setText("vibrate " + vibrate);
            }
          });

      final TextView volume_status = new TextView(c);
      volume_status.setText("volume " + s.volume_starting + " to " + s.volume_ending + " over " + s.volume_time);

      final SeekBar edit_volume_starting = new SeekBar(c);
      edit_volume_starting.setMax(20);
      edit_volume_starting.setProgress(s.volume_starting / 5);

      final SeekBar edit_volume_ending = new SeekBar(c);
      edit_volume_ending.setMax(20);
      edit_volume_ending.setProgress(s.volume_ending / 5);

      final SeekBar edit_volume_time = new SeekBar(c);
      edit_volume_time.setMax(12);
      edit_volume_time.setProgress(s.volume_time / 5);

      edit_volume_starting.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean user) {
              final int volume_starting = edit_volume_starting.getProgress() * 5;
              final int volume_ending = edit_volume_ending.getProgress() * 5;
              final int volume_time = edit_volume_time.getProgress() * 5;
              if (user && volume_ending < volume_starting) {
                edit_volume_ending.setProgress(volume_starting / 5);
                volume_status.setText("volume " + volume_starting + " to " + volume_starting + " over " + volume_time);
              } else {
                volume_status.setText("volume " + volume_starting + " to " + volume_ending + " over " + volume_time);
              }
            }
            @Override
            public void onStartTrackingTouch(SeekBar s) {}
            @Override
            public void onStopTrackingTouch(SeekBar s) {
              ContentValues val = new ContentValues();
              val.put(AlarmClockProvider.SettingsEntry.VOLUME_STARTING,
                      edit_volume_starting.getProgress() * 5);
              val.put(AlarmClockProvider.SettingsEntry.VOLUME_ENDING,
                      edit_volume_ending.getProgress() * 5);
              if (c.getContentResolver().update(settings, val, null, null) < 1)
                c.getContentResolver().insert(settings, val);
            }
          });

      edit_volume_ending.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean user) {
              final int volume_starting = edit_volume_starting.getProgress() * 5;
              final int volume_ending = edit_volume_ending.getProgress() * 5;
              final int volume_time = edit_volume_time.getProgress() * 5;
              if (user && volume_ending < volume_starting) {
                edit_volume_starting.setProgress(volume_ending / 5);
                volume_status.setText("volume " + volume_ending + " to " + volume_ending + " over " + volume_time);
              } else {
                volume_status.setText("volume " + volume_starting + " to " + volume_ending + " over " + volume_time);
              }
            }
            @Override
            public void onStartTrackingTouch(SeekBar s) {}
            @Override
            public void onStopTrackingTouch(SeekBar s) {
              ContentValues val = new ContentValues();
              val.put(AlarmClockProvider.SettingsEntry.VOLUME_STARTING,
                      edit_volume_starting.getProgress() * 5);
              val.put(AlarmClockProvider.SettingsEntry.VOLUME_ENDING,
                      edit_volume_ending.getProgress() * 5);
              if (c.getContentResolver().update(settings, val, null, null) < 1)
                c.getContentResolver().insert(settings, val);
            }
          });

      edit_volume_time.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean user) {
              final int volume_starting = edit_volume_starting.getProgress() * 5;
              final int volume_ending = edit_volume_ending.getProgress() * 5;
              final int volume_time = edit_volume_time.getProgress() * 5;
              volume_status.setText("volume " + volume_starting + " to " + volume_ending + " over " + volume_time);
            }
            @Override
            public void onStartTrackingTouch(SeekBar s) {}
            @Override
            public void onStopTrackingTouch(SeekBar s) {
              ContentValues val = new ContentValues();
              val.put(AlarmClockProvider.SettingsEntry.VOLUME_TIME,
                      s.getProgress() * 5);
              if (c.getContentResolver().update(settings, val, null, null) < 1)
                c.getContentResolver().insert(settings, val);
            }
          });

      volume_layout = new LinearLayout(c);
      volume_layout.setOrientation(LinearLayout.VERTICAL);
      volume_layout.addView(volume_status);
      volume_layout.addView(edit_volume_starting);
      volume_layout.addView(edit_volume_ending);
      volume_layout.addView(edit_volume_time);
    }
  }
}
