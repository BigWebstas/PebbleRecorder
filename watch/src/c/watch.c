#include <pebble.h>

// Pebble apps have no runtime API to read their own appinfo version, so this is kept in sync
// with watch/package.json's "version" field by hand.
#define APP_VERSION "1.5.3"

// Max distance (px) a touch may travel between touchdown and liftoff and still count as a tap
// rather than a swipe/drag. Anything past this is ignored.
#define TOUCH_TAP_SLOP_PX 24

// How long to wait after launch (or reconnect) for the phone to push a STATUS reply before
// flagging it as unresponsive. The phone side replies as soon as the watchapp opens (see
// PebbleListenerService.onAppOpened), so this only fires when something's actually wrong -
// the service isn't running/bound, or the companion app rejected the message.
#define PHONE_CHECK_TIMEOUT_MS 3000

// How long the "Connected" success banner stays up before auto-hiding.
#define CONNECTED_BANNER_MS 1500

// Values sent watch -> phone via MESSAGE_KEY_COMMAND.
typedef enum {
  COMMAND_STOP = 0,
  COMMAND_START = 1,
  COMMAND_PAUSE = 2,
  COMMAND_RESUME = 3,
  COMMAND_BOOKMARK = 4,
} Command;

// Values received phone -> watch via MESSAGE_KEY_NOTICE.
typedef enum {
  NOTICE_TRANSCRIBED = 1,
  NOTICE_TRANSCRIPTION_FAILED = 2,
} Notice;

// How long a notice banner ("Transcribed") stays up.
#define NOTICE_BANNER_MS 2500

// Silence auto-stop threshold (1000 / 32768 ~= -30.3 dBFS) on the 0..100 (-60..0 dBFS) level bar.
#define SILENCE_THRESHOLD_LEVEL 49

// The phone samples the mic every 100 ms and sends the samples in batches of four (MESSAGE_KEY_LEVELS)
// every 400 ms. They're played back one per LEVEL_SAMPLE_MS so the bar moves smoothly.
#define LEVEL_SAMPLE_MS 100
#define LEVEL_QUEUE_SIZE 16
// If messages bunch up, drop the oldest samples so the meter doesn't lag behind the room.
#define LEVEL_QUEUE_MAX_BACKLOG 8
// One history column per batch (400 ms), drawn from that batch's peak.
#define LEVEL_SAMPLES_PER_COLUMN 4
#define HISTORY_MAX_COLUMNS 200
// Peak hold: the marker stays at the loudest recent sample for 1.5 s, then falls back.
#define PEAK_HOLD_SAMPLES 15
#define PEAK_DECAY_PER_SAMPLE 4

// Values received phone -> watch via MESSAGE_KEY_STATUS.
typedef enum {
  STATUS_IDLE = 0,
  STATUS_RECORDING = 1,
  STATUS_ERROR = 2,
  STATUS_PAUSED = 3,
} Status;

typedef enum {
  APP_STATE_IDLE,
  APP_STATE_STARTING,
  APP_STATE_RECORDING,
  APP_STATE_STOPPING,
  APP_STATE_PAUSING,
  APP_STATE_PAUSED,
  APP_STATE_RESUMING,
  APP_STATE_ERROR,
  APP_STATE_NO_PHONE,
} AppState;

static Window *s_window;
static TextLayer *s_status_layer;
static TextLayer *s_timer_layer;
static TextLayer *s_banner_layer;
static Layer *s_icon_layer;
static Layer *s_level_layer;
static Layer *s_history_layer;
static TextLayer *s_clock_layer;
static TextLayer *s_version_layer;
static char s_clock_buffer[8];
static int s_level; // mic level 0..100 streamed from the phone whenever the app is open
static int s_peak;  // peak-hold marker on the level bar, 0..100
static int s_peak_hold; // samples left before the peak marker starts to fall
static uint8_t s_level_queue[LEVEL_QUEUE_SIZE]; // samples waiting to be played, ring buffer
static int s_level_queue_head;
static int s_level_queue_count;
static AppTimer *s_level_timer;
static uint8_t s_history[HISTORY_MAX_COLUMNS]; // per-column peaks, ring buffer, oldest at s_history_next
static int s_history_next;
static int s_history_len;
static int s_history_columns; // strip width in px, one column per px
static int s_column_peak;
static int s_column_samples;
static AppState s_state = APP_STATE_NO_PHONE;
static time_t s_recording_start_time;
static time_t s_pause_started_at; // 0 when not currently paused
static char s_timer_buffer[12];
static char s_bookmark_banner_buffer[24];
static GColor s_icon_color;
static GPoint s_touch_down_point; // touchdown coords, for tap-vs-swipe classification
static bool s_touch_is_tap;       // gesture still qualifies as a tap (hasn't travelled too far)
static AppTimer *s_phone_check_timer;
static AppTimer *s_banner_hide_timer;
static bool s_got_phone_reply;
static bool s_phone_check_pending; // true from launch/reconnect until a reply arrives or it times out

// Draws a mic icon by default, swapping to a record (filled circle) icon while actively
// recording, a stop (filled square) icon while idle (ready to start), and a pause (two bars)
// icon while paused.
static void prv_icon_layer_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  GPoint center = grect_center_point(&bounds);

  graphics_context_set_fill_color(ctx, s_icon_color);
  graphics_context_set_stroke_color(ctx, s_icon_color);
  graphics_context_set_stroke_width(ctx, 3);

  switch (s_state) {
    case APP_STATE_RECORDING:
      graphics_fill_circle(ctx, center, 14);
      break;
    case APP_STATE_IDLE:
      graphics_fill_rect(ctx, GRect(center.x - 14, center.y - 14, 28, 28), 0, GCornerNone);
      break;
    case APP_STATE_PAUSED:
      graphics_fill_rect(ctx, GRect(center.x - 14, center.y - 14, 10, 28), 0, GCornerNone);
      graphics_fill_rect(ctx, GRect(center.x + 4, center.y - 14, 10, 28), 0, GCornerNone);
      break;
    default: {
      GRect mic_head = GRect(center.x - 7, center.y - 18, 14, 22);
      graphics_fill_rect(ctx, mic_head, 7, GCornersAll);
      graphics_draw_line(ctx, GPoint(center.x, center.y + 6), GPoint(center.x, center.y + 16));
      graphics_draw_line(ctx, GPoint(center.x - 10, center.y + 16), GPoint(center.x + 10, center.y + 16));
      break;
    }
  }
}

// Level meter: an outlined bar filled in proportion to the phone's mic level, so it's obvious at
// a glance that the phone is actually hearing something, with a vertical tick mark at the silence
// auto-stop threshold.
static void prv_level_layer_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  GRect bar = GRect(0, 3, bounds.size.w, bounds.size.h - 6);
  graphics_context_set_fill_color(ctx, s_icon_color);
  graphics_context_set_stroke_color(ctx, s_icon_color);
  graphics_draw_rect(ctx, bar);
  int inner_w = bar.size.w - 4;
  int fill = inner_w * s_level / 100;
  graphics_fill_rect(ctx, GRect(2, bar.origin.y + 2, fill, bar.size.h - 4), 0, GCornerNone);

  // Peak hold: a 2 px marker at the loudest recent level, once the live level has dropped below it.
  int peak_x = inner_w * s_peak / 100;
  if (peak_x > fill + 1) {
    if (peak_x > inner_w - 2) {
      peak_x = inner_w - 2;
    }
    graphics_fill_rect(ctx, GRect(2 + peak_x, bar.origin.y + 2, 2, bar.size.h - 4), 0, GCornerNone);
  }

  int threshold_x = 2 + inner_w * SILENCE_THRESHOLD_LEVEL / 100;
  graphics_draw_line(ctx, GPoint(threshold_x, 0), GPoint(threshold_x, bounds.size.h - 1));
  if (fill >= threshold_x - 1) {
    GColor bg = (s_state == APP_STATE_RECORDING)
      ? PBL_IF_COLOR_ELSE(GColorRed, GColorWhite)
      : (s_state == APP_STATE_PAUSED ? PBL_IF_COLOR_ELSE(GColorBlue, GColorWhite) : GColorWhite);
    graphics_context_set_stroke_color(ctx, bg);
    graphics_draw_line(ctx, GPoint(threshold_x, bar.origin.y + 2),
                       GPoint(threshold_x, bar.origin.y + bar.size.h - 3));
  }
}

// Level history: one column per 400 ms batch, newest on the right, with a dotted line at the
// silence auto-stop threshold, so a quiet stretch heading for an auto-stop is easy to spot.
static void prv_history_layer_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  int bottom = bounds.size.h - 1;
  int max_h = bounds.size.h - 1;
  graphics_context_set_stroke_color(ctx, s_icon_color);
  graphics_draw_line(ctx, GPoint(0, bottom), GPoint(bounds.size.w - 1, bottom));

  int start = s_history_columns - s_history_len;
  for (int i = 0; i < s_history_len; i++) {
    int level = s_history[(s_history_next - s_history_len + i + HISTORY_MAX_COLUMNS) % HISTORY_MAX_COLUMNS];
    int h = max_h * level / 100;
    if (h > 0) {
      graphics_draw_line(ctx, GPoint(start + i, bottom - h), GPoint(start + i, bottom));
    }
  }

  int threshold_y = bottom - max_h * SILENCE_THRESHOLD_LEVEL / 100;
  for (int x = 0; x < bounds.size.w; x += 3) {
    graphics_draw_pixel(ctx, GPoint(x, threshold_y));
  }
}

static void prv_clear_levels(void) {
  s_level = 0;
  s_peak = 0;
  s_peak_hold = 0;
  s_level_queue_count = 0;
  s_column_peak = 0;
  s_column_samples = 0;
  s_history_len = 0;
  if (s_level_timer) {
    app_timer_cancel(s_level_timer);
    s_level_timer = NULL;
  }
}

static void prv_play_level(int level) {
  if (level < 0) {
    level = 0;
  } else if (level > 100) {
    level = 100;
  }
  s_level = level;

  if (level >= s_peak) {
    s_peak = level;
    s_peak_hold = PEAK_HOLD_SAMPLES;
  } else if (s_peak_hold > 0) {
    s_peak_hold--;
  } else {
    s_peak -= PEAK_DECAY_PER_SAMPLE;
    if (s_peak < level) {
      s_peak = level;
    }
  }
  layer_mark_dirty(s_level_layer);

  if (level > s_column_peak) {
    s_column_peak = level;
  }
  if (++s_column_samples >= LEVEL_SAMPLES_PER_COLUMN) {
    s_history[s_history_next] = s_column_peak;
    s_history_next = (s_history_next + 1) % HISTORY_MAX_COLUMNS;
    if (s_history_len < s_history_columns) {
      s_history_len++;
    }
    s_column_peak = 0;
    s_column_samples = 0;
    layer_mark_dirty(s_history_layer);
  }
}

// Plays the next queued sample, then schedules the one after it.
static void prv_level_timer_callback(void *data) {
  s_level_timer = NULL;
  if (s_level_queue_count == 0) {
    return;
  }
  prv_play_level(s_level_queue[s_level_queue_head]);
  s_level_queue_head = (s_level_queue_head + 1) % LEVEL_QUEUE_SIZE;
  s_level_queue_count--;
  if (s_level_queue_count > 0) {
    s_level_timer = app_timer_register(LEVEL_SAMPLE_MS, prv_level_timer_callback, NULL);
  }
}

static void prv_queue_levels(const uint8_t *levels, int count) {
  for (int i = 0; i < count; i++) {
    if (s_level_queue_count == LEVEL_QUEUE_SIZE) {
      s_level_queue_head = (s_level_queue_head + 1) % LEVEL_QUEUE_SIZE;
      s_level_queue_count--;
    }
    s_level_queue[(s_level_queue_head + s_level_queue_count) % LEVEL_QUEUE_SIZE] = levels[i];
    s_level_queue_count++;
  }
  while (s_level_queue_count > LEVEL_QUEUE_MAX_BACKLOG) {
    s_level_queue_head = (s_level_queue_head + 1) % LEVEL_QUEUE_SIZE;
    s_level_queue_count--;
  }
  if (!s_level_timer) {
    prv_level_timer_callback(NULL);
  }
}

static GColor prv_background_color_for_state(AppState state) {
  switch (state) {
    case APP_STATE_RECORDING: return PBL_IF_COLOR_ELSE(GColorRed, GColorWhite);
    case APP_STATE_PAUSED:    return PBL_IF_COLOR_ELSE(GColorBlue, GColorWhite);
    default:                  return GColorWhite;
  }
}

static void prv_update_clock(const struct tm *tick_time) {
  int hour = tick_time->tm_hour;
  if (clock_is_24h_style()) {
    snprintf(s_clock_buffer, sizeof(s_clock_buffer), "%02d:%02d", hour, tick_time->tm_min);
  } else {
    hour %= 12;
    if (hour == 0) {
      hour = 12;
    }
    snprintf(s_clock_buffer, sizeof(s_clock_buffer), "%d:%02d", hour, tick_time->tm_min);
  }
  text_layer_set_text(s_clock_layer, s_clock_buffer);
}

static void prv_update_timer_text(void) {
  int elapsed = (int)(time(NULL) - s_recording_start_time);
  if (elapsed < 0) {
    elapsed = 0;
  }
  snprintf(s_timer_buffer, sizeof(s_timer_buffer), "%02d:%02d", elapsed / 60, elapsed % 60);
  text_layer_set_text(s_timer_layer, s_timer_buffer);
}

static void prv_tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  if (s_state == APP_STATE_RECORDING || s_state == APP_STATE_PAUSING) {
    prv_update_timer_text();
  }
  if (units_changed & MINUTE_UNIT) {
    prv_update_clock(tick_time);
  }
}

static void prv_set_state(AppState state) {
  s_state = state;

  const char *label;
  switch (state) {
    case APP_STATE_IDLE:      label = "Idle";        break;
    case APP_STATE_STARTING:  label = "Starting...";  break;
    case APP_STATE_RECORDING: label = "Recording";   break;
    case APP_STATE_STOPPING:  label = "Stopping...";  break;
    case APP_STATE_PAUSING:   label = "Pausing...";  break;
    case APP_STATE_PAUSED:    label = "Paused";      break;
    case APP_STATE_RESUMING:  label = "Resuming...";  break;
    case APP_STATE_ERROR:     label = "Error";       break;
    case APP_STATE_NO_PHONE:  label = "No Phone";    break;
    default:                  label = "?";           break;
  }
  text_layer_set_text(s_status_layer, label);

  // Color platforms turn red while recording and blue while paused; B&W platforms can't
  // represent either, so they keep the default white background and rely on the text label.
  GColor background = prv_background_color_for_state(state);
  window_set_background_color(s_window, background);

  bool light_foreground = PBL_IF_COLOR_ELSE(
    state == APP_STATE_RECORDING || state == APP_STATE_PAUSED, false);
  GColor foreground = light_foreground ? GColorWhite : GColorBlack;
  s_icon_color = foreground;
  text_layer_set_text_color(s_status_layer, foreground);
  text_layer_set_text_color(s_timer_layer, foreground);
  layer_mark_dirty(s_icon_layer);
  text_layer_set_text_color(s_clock_layer, foreground);
  text_layer_set_text_color(s_version_layer, foreground);

  // The phone streams the mic level whenever the app is open and connected, recording or not.
  bool show_levels = state != APP_STATE_NO_PHONE && state != APP_STATE_ERROR;
  if (!show_levels) {
    prv_clear_levels();
  }
  layer_set_hidden(s_level_layer, !show_levels);
  layer_set_hidden(s_history_layer, !show_levels);
  layer_mark_dirty(s_level_layer);
  layer_mark_dirty(s_history_layer);

  tick_timer_service_unsubscribe();
  switch (state) {
    case APP_STATE_RECORDING:
      if (s_pause_started_at != 0) {
        // Resuming: shift the start time forward by however long we were paused, so the
        // elapsed timer continues from where it left off instead of jumping or resetting.
        s_recording_start_time += time(NULL) - s_pause_started_at;
        s_pause_started_at = 0;
      } else {
        s_recording_start_time = time(NULL);
      }
      prv_update_timer_text();
      tick_timer_service_subscribe(SECOND_UNIT | MINUTE_UNIT, prv_tick_handler);
      break;
    case APP_STATE_PAUSING:
      // Still actually recording until the phone confirms the pause.
      prv_update_timer_text();
      tick_timer_service_subscribe(SECOND_UNIT | MINUTE_UNIT, prv_tick_handler);
      break;
    case APP_STATE_PAUSED:
      if (s_pause_started_at == 0) {
        s_pause_started_at = time(NULL);
      }
      tick_timer_service_subscribe(MINUTE_UNIT, prv_tick_handler);
      break;
    case APP_STATE_RESUMING:
      // Still paused until the phone confirms the resume; leave the frozen value as-is.
      tick_timer_service_subscribe(MINUTE_UNIT, prv_tick_handler);
      break;
    default:
      s_pause_started_at = 0;
      text_layer_set_text(s_timer_layer, "");
      tick_timer_service_subscribe(MINUTE_UNIT, prv_tick_handler);
      break;
  }
}

static void prv_cancel_banner_hide(void) {
  if (s_banner_hide_timer) {
    app_timer_cancel(s_banner_hide_timer);
    s_banner_hide_timer = NULL;
  }
}

static void prv_hide_banner(void) {
  prv_cancel_banner_hide();
  layer_set_hidden(text_layer_get_layer(s_banner_layer), true);
}

static void prv_show_banner_with_color(const char *text, GColor background) {
  prv_cancel_banner_hide();
  text_layer_set_background_color(s_banner_layer, background);
  text_layer_set_text(s_banner_layer, text);
  layer_set_hidden(text_layer_get_layer(s_banner_layer), false);
}

static void prv_show_error_banner(const char *text) {
  prv_show_banner_with_color(text, PBL_IF_COLOR_ELSE(GColorRed, GColorBlack));
}

static void prv_banner_hide_timeout(void *data) {
  s_banner_hide_timer = NULL;
  prv_hide_banner();
}

static void prv_show_connected_banner(void) {
  prv_show_banner_with_color("Connected", PBL_IF_COLOR_ELSE(GColorGreen, GColorBlack));
  s_banner_hide_timer = app_timer_register(CONNECTED_BANNER_MS, prv_banner_hide_timeout, NULL);
}

// Flashes a banner and buzzes when the phone finishes transcribing a recording.
static void prv_show_notice(Notice notice) {
  if (s_banner_hide_timer) {
    app_timer_cancel(s_banner_hide_timer);
  }
  if (notice == NOTICE_TRANSCRIBED) {
    prv_show_banner_with_color("Transcribed", PBL_IF_COLOR_ELSE(GColorGreen, GColorBlack));
    vibes_double_pulse();
  } else {
    prv_show_error_banner("Transcription failed");
    vibes_long_pulse();
  }
  s_banner_hide_timer = app_timer_register(NOTICE_BANNER_MS, prv_banner_hide_timeout, NULL);
}

static void prv_cancel_phone_check(void) {
  s_phone_check_pending = false;
  if (s_phone_check_timer) {
    app_timer_cancel(s_phone_check_timer);
    s_phone_check_timer = NULL;
  }
}

static void prv_phone_check_timeout(void *data) {
  s_phone_check_timer = NULL;
  s_phone_check_pending = false;
  if (!s_got_phone_reply) {
    prv_show_error_banner("Phone app not responding");
  }
}

// Called on launch (and on bluetooth reconnect) to flag a phone that's connected over bluetooth
// but whose companion/Android app never replies - e.g. the service isn't running or the
// companion app rejected the message. A healthy phone replies almost immediately (see
// PebbleListenerService.onAppOpened), so this timeout only trips when something's wrong.
static void prv_start_phone_check(void) {
  s_got_phone_reply = false;
  s_phone_check_pending = true;
  if (s_phone_check_timer) {
    app_timer_cancel(s_phone_check_timer);
  }
  s_phone_check_timer = app_timer_register(PHONE_CHECK_TIMEOUT_MS, prv_phone_check_timeout, NULL);
}

static AppState prv_pending_state_for_command(Command command) {
  switch (command) {
    case COMMAND_START:  return APP_STATE_STARTING;
    case COMMAND_STOP:   return APP_STATE_STOPPING;
    case COMMAND_PAUSE:  return APP_STATE_PAUSING;
    case COMMAND_RESUME: return APP_STATE_RESUMING;
    default:              return APP_STATE_ERROR;
  }
}

static void prv_send_command(Command command) {
  DictionaryIterator *iter;
  AppMessageResult result = app_message_outbox_begin(&iter);
  if (result != APP_MSG_OK) {
    prv_set_state(APP_STATE_NO_PHONE);
    return;
  }

  int32_t command_value = command;
  dict_write_int(iter, MESSAGE_KEY_COMMAND, &command_value, sizeof(command_value), true);
  result = app_message_outbox_send();
  if (result != APP_MSG_OK) {
    prv_set_state(APP_STATE_NO_PHONE);
    return;
  }

  prv_set_state(prv_pending_state_for_command(command));
}

// Marks the current point of the recording on the phone. Unlike the other commands it doesn't
// change the watch's state, so it bypasses prv_send_command's pending-state handling.
static void prv_bookmark_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (s_state != APP_STATE_RECORDING) {
    return;
  }
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }
  int32_t command_value = COMMAND_BOOKMARK;
  dict_write_int(iter, MESSAGE_KEY_COMMAND, &command_value, sizeof(command_value), true);
  if (app_message_outbox_send() != APP_MSG_OK) {
    return;
  }
  int elapsed = (int)(time(NULL) - s_recording_start_time);
  if (elapsed < 0) {
    elapsed = 0;
  }
  snprintf(s_bookmark_banner_buffer, sizeof(s_bookmark_banner_buffer),
           "Bookmark %02d:%02d", elapsed / 60, elapsed % 60);
  prv_show_banner_with_color(s_bookmark_banner_buffer, GColorBlack);
  s_banner_hide_timer = app_timer_register(CONNECTED_BANNER_MS, prv_banner_hide_timeout, NULL);
  vibes_short_pulse();
}

static void prv_select_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (s_state == APP_STATE_RECORDING || s_state == APP_STATE_PAUSED) {
    prv_send_command(COMMAND_STOP);
  } else if (s_state == APP_STATE_IDLE || s_state == APP_STATE_ERROR) {
    prv_send_command(COMMAND_START);
  }
  // Ignore presses while STARTING/STOPPING/PAUSING/RESUMING (waiting on phone) or NO_PHONE.
}

// A screen tap (on touch platforms like Pebble Time 2) starts a recording, then toggles it
// between recording and paused. Stop stays on the SELECT button.
static void prv_handle_tap_toggle(void) {
  switch (s_state) {
    case APP_STATE_IDLE:
    case APP_STATE_ERROR:
      prv_send_command(COMMAND_START);
      break;
    case APP_STATE_RECORDING:
      prv_send_command(COMMAND_PAUSE);
      break;
    case APP_STATE_PAUSED:
      prv_send_command(COMMAND_RESUME);
      break;
    default:
      // STARTING/STOPPING/PAUSING/RESUMING (waiting on phone) or NO_PHONE: ignore.
      break;
  }
}

static void prv_touch_handler(const TouchEvent *event, void *context) {
  switch (event->type) {
    case TouchEvent_Touchdown:
      s_touch_down_point = GPoint(event->x, event->y);
      s_touch_is_tap = true;
      break;
    case TouchEvent_PositionUpdate: {
      if (!s_touch_is_tap) {
        break;
      }
      int dx = event->x - s_touch_down_point.x;
      int dy = event->y - s_touch_down_point.y;
      if (dx * dx + dy * dy > TOUCH_TAP_SLOP_PX * TOUCH_TAP_SLOP_PX) {
        s_touch_is_tap = false; // travelled too far - a swipe/drag, not a tap
      }
      break;
    }
    case TouchEvent_Liftoff:
      if (s_touch_is_tap) {
        s_touch_is_tap = false;
        prv_handle_tap_toggle();
      }
      break;
  }
}

static void prv_pause_resume_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (s_state == APP_STATE_RECORDING) {
    prv_send_command(COMMAND_PAUSE);
  } else if (s_state == APP_STATE_PAUSED) {
    prv_send_command(COMMAND_RESUME);
  }
  // Ignore presses in any other state.
}

static void prv_click_config_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click_handler);
  window_single_click_subscribe(BUTTON_ID_UP, prv_bookmark_click_handler);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_pause_resume_click_handler);
}

// The phone reports how long the recording has actually run (pauses excluded) alongside
// RECORDING/PAUSED. Adopting it keeps the timer right when the watch app was closed or
// disconnected mid-recording and prv_set_state would otherwise restart it from zero.
static void prv_sync_elapsed(DictionaryIterator *iterator, bool paused) {
  Tuple *elapsed_tuple = dict_find(iterator, MESSAGE_KEY_ELAPSED);
  if (!elapsed_tuple) {
    return;
  }
  time_t now = time(NULL);
  s_recording_start_time = now - elapsed_tuple->value->int32;
  s_pause_started_at = paused ? now : 0;
  prv_update_timer_text();
}

// Touch is on by default; the phone can turn it off via MESSAGE_KEY_TOUCH_ENABLED (sent with
// every STATUS reply). touch_service_is_enabled() is false on platforms without a touchscreen.
static void prv_set_touch_enabled(bool enabled) {
  touch_service_unsubscribe();
  if (enabled && touch_service_is_enabled()) {
    touch_service_subscribe(prv_touch_handler, NULL);
  }
}

static void prv_inbox_received_handler(DictionaryIterator *iterator, void *context) {
  Tuple *levels_tuple = dict_find(iterator, MESSAGE_KEY_LEVELS);
  if (levels_tuple) {
    prv_queue_levels(levels_tuple->value->data, levels_tuple->length);
    return;
  }
  Tuple *level_tuple = dict_find(iterator, MESSAGE_KEY_LEVEL);
  if (level_tuple) {
    // A phone app from before LEVELS sends one level per 400 ms: hold it for the whole batch.
    uint8_t level = level_tuple->value->int32;
    uint8_t batch[LEVEL_SAMPLES_PER_COLUMN] = {level, level, level, level};
    prv_queue_levels(batch, LEVEL_SAMPLES_PER_COLUMN);
    return;
  }

  Tuple *notice_tuple = dict_find(iterator, MESSAGE_KEY_NOTICE);
  if (notice_tuple) {
    prv_show_notice((Notice)notice_tuple->value->int32);
    return;
  }

  Tuple *touch_tuple = dict_find(iterator, MESSAGE_KEY_TOUCH_ENABLED);
  if (touch_tuple) {
    prv_set_touch_enabled(touch_tuple->value->int32 != 0);
  }

  Tuple *status_tuple = dict_find(iterator, MESSAGE_KEY_STATUS);
  if (!status_tuple) {
    return;
  }

  Status status = (Status)status_tuple->value->int32;

  s_got_phone_reply = true;
  bool was_pending = s_phone_check_pending;
  bool already_recording = (s_state == APP_STATE_RECORDING && status == STATUS_RECORDING);
  prv_cancel_phone_check();
  if (was_pending && status != STATUS_ERROR) {
    prv_show_connected_banner();
  } else if (!already_recording) {
    prv_hide_banner();
  }

  switch (status) {
    case STATUS_IDLE:
      prv_set_state(APP_STATE_IDLE);
      break;
    case STATUS_RECORDING:
      prv_set_state(APP_STATE_RECORDING);
      prv_sync_elapsed(iterator, false);
      if (!already_recording) {
        vibes_short_pulse();
      }
      break;
    case STATUS_PAUSED:
      prv_set_state(APP_STATE_PAUSED);
      prv_sync_elapsed(iterator, true);
      vibes_short_pulse();
      break;
    case STATUS_ERROR:
      prv_set_state(APP_STATE_ERROR);
      vibes_double_pulse();
      break;
  }
}

static void prv_outbox_failed_handler(DictionaryIterator *iterator, AppMessageResult reason, void *context) {
  prv_cancel_phone_check();
  prv_hide_banner();
  prv_set_state(APP_STATE_NO_PHONE);
}

static void prv_bluetooth_connection_handler(bool connected) {
  if (!connected) {
    prv_cancel_phone_check();
    prv_hide_banner();
    prv_set_state(APP_STATE_NO_PHONE);
  } else if (s_state == APP_STATE_NO_PHONE) {
    prv_set_state(APP_STATE_IDLE);
    prv_start_phone_check();
  }
}

static void prv_window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);
  int top_offset = bounds.size.h >= 200 ? 14 : 0;

  s_icon_layer = layer_create(GRect(0, 8 + top_offset, bounds.size.w, 40));
  layer_set_update_proc(s_icon_layer, prv_icon_layer_update_proc);
  layer_add_child(window_layer, s_icon_layer);

  GRect level_frame = GRect(bounds.size.w / 5, 102 + top_offset, bounds.size.w * 3 / 5, 14);
  s_level_layer = layer_create(level_frame);
  layer_set_update_proc(s_level_layer, prv_level_layer_update_proc);
  layer_add_child(window_layer, s_level_layer);

  int history_top = 118 + top_offset;
  int history_h = bounds.size.h >= 200 ? 28 : 20;
#if defined(PBL_ROUND)
  // Keep the strip's bottom corners inside the circle.
  int radius = bounds.size.w / 2;
  int dx = radius - level_frame.origin.x;
  while (history_h > 6) {
    int dy = history_top + history_h - bounds.size.h / 2;
    if (dx * dx + dy * dy <= (radius - 4) * (radius - 4)) {
      break;
    }
    history_h--;
  }
#endif
  s_history_columns = level_frame.size.w;
  if (s_history_columns > HISTORY_MAX_COLUMNS) {
    s_history_columns = HISTORY_MAX_COLUMNS;
  }
  s_history_layer = layer_create(GRect(level_frame.origin.x, history_top, s_history_columns, history_h));
  layer_set_update_proc(s_history_layer, prv_history_layer_update_proc);
  layer_add_child(window_layer, s_history_layer);

  int clock_top = history_top + history_h + (bounds.size.h >= 200 ? 6 : 5);
  bool tall = bounds.size.h >= 200;
  s_clock_layer = text_layer_create(GRect(0, clock_top - (tall ? 6 : 10), bounds.size.w, tall ? 52 : 38));
  text_layer_set_text_alignment(s_clock_layer, GTextAlignmentCenter);
  text_layer_set_font(s_clock_layer, fonts_get_system_font(
      tall ? FONT_KEY_BITHAM_42_MEDIUM_NUMBERS : FONT_KEY_LECO_32_BOLD_NUMBERS));
  text_layer_set_background_color(s_clock_layer, GColorClear);
  layer_add_child(window_layer, text_layer_get_layer(s_clock_layer));

  time_t now = time(NULL);
  struct tm *tick_time = localtime(&now);
  if (tick_time) {
    prv_update_clock(tick_time);
  }

  s_version_layer = text_layer_create(GRect(2, 0, bounds.size.w / 2, 22));
  text_layer_set_text_alignment(s_version_layer, GTextAlignmentLeft);
  text_layer_set_font(s_version_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_background_color(s_version_layer, GColorClear);
  text_layer_set_text(s_version_layer, "v" APP_VERSION);
  layer_add_child(window_layer, text_layer_get_layer(s_version_layer));

  s_status_layer = text_layer_create(GRect(0, 44 + top_offset, bounds.size.w, 28));
  text_layer_set_text_alignment(s_status_layer, GTextAlignmentCenter);
  text_layer_set_font(s_status_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  text_layer_set_background_color(s_status_layer, GColorClear);
  layer_add_child(window_layer, text_layer_get_layer(s_status_layer));

  s_timer_layer = text_layer_create(GRect(0, 68 + top_offset, bounds.size.w, 32));
  text_layer_set_text_alignment(s_timer_layer, GTextAlignmentCenter);
  text_layer_set_font(s_timer_layer, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  text_layer_set_background_color(s_timer_layer, GColorClear);
  layer_add_child(window_layer, text_layer_get_layer(s_timer_layer));

  // Overlaid on top; hidden unless a phone check is reporting success/failure.
  s_banner_layer = text_layer_create(GRect(0, 0, bounds.size.w, 46));
  text_layer_set_text_alignment(s_banner_layer, GTextAlignmentCenter);
  text_layer_set_font(s_banner_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_background_color(s_banner_layer, PBL_IF_COLOR_ELSE(GColorRed, GColorBlack));
  text_layer_set_text_color(s_banner_layer, GColorWhite);
  layer_set_hidden(text_layer_get_layer(s_banner_layer), true);
  layer_add_child(window_layer, text_layer_get_layer(s_banner_layer));

  bool connected = connection_service_peek_pebble_app_connection();
  prv_set_state(connected ? APP_STATE_IDLE : APP_STATE_NO_PHONE);
  if (connected) {
    prv_start_phone_check();
  }

  // Touch platforms (e.g. Pebble Time 2 / emery): a tap toggles start/pause. No-op elsewhere.
  prv_set_touch_enabled(true);
}

#if !PBL_PLATFORM_APLITE
// Launcher subtitle ("glance") showing the state the app was left in. Apps can only set it while
// running, so it's written on exit; a recording started or stopped from the phone afterwards
// isn't reflected until the app is opened again. While recording, the firmware keeps the
// elapsed time ticking from the template on its own.
static void prv_glance_reload_callback(AppGlanceReloadSession *session, size_t limit, void *context) {
  if (limit < 1) {
    return;
  }
  char subtitle[64];
  switch (s_state) {
    case APP_STATE_STARTING:
    case APP_STATE_RECORDING:
    case APP_STATE_PAUSING:
      snprintf(subtitle, sizeof(subtitle), "Recording {time_since(%ld)|format('%%T')}",
               (long)s_recording_start_time);
      break;
    case APP_STATE_PAUSED:
    case APP_STATE_RESUMING: {
      int elapsed = (int)((s_pause_started_at ? s_pause_started_at : time(NULL)) - s_recording_start_time);
      if (elapsed < 0) {
        elapsed = 0;
      }
      snprintf(subtitle, sizeof(subtitle), "Paused %02d:%02d", elapsed / 60, elapsed % 60);
      break;
    }
    default:
      snprintf(subtitle, sizeof(subtitle), "Idle");
      break;
  }
  AppGlanceSlice slice = {
    .expiration_time = APP_GLANCE_SLICE_NO_EXPIRATION,
    .layout.icon = APP_GLANCE_SLICE_DEFAULT_ICON,
    .layout.subtitle_template_string = subtitle,
  };
  AppGlanceResult result = app_glance_add_slice(session, slice);
  if (result != APP_GLANCE_RESULT_SUCCESS) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "app_glance_add_slice() returned %d", result);
  }
}
#endif

static void prv_window_unload(Window *window) {
#if !PBL_PLATFORM_APLITE
  app_glance_reload(prv_glance_reload_callback, NULL);
#endif
  touch_service_unsubscribe();
  prv_cancel_phone_check();
  prv_cancel_banner_hide();
  text_layer_destroy(s_status_layer);
  text_layer_destroy(s_timer_layer);
  text_layer_destroy(s_banner_layer);
  layer_destroy(s_icon_layer);
  prv_clear_levels();
  layer_destroy(s_level_layer);
  layer_destroy(s_history_layer);
  text_layer_destroy(s_clock_layer);
  text_layer_destroy(s_version_layer);
}

static void prv_init(void) {
  app_message_register_inbox_received(prv_inbox_received_handler);
  app_message_register_outbox_failed(prv_outbox_failed_handler);
  app_message_open(app_message_inbox_size_maximum(), app_message_outbox_size_maximum());

  connection_service_subscribe((ConnectionHandlers) {
    .pebble_app_connection_handler = prv_bluetooth_connection_handler,
  });

  s_window = window_create();
  window_set_click_config_provider(s_window, prv_click_config_provider);
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  const bool animated = true;
  window_stack_push(s_window, animated);
}

static void prv_deinit(void) {
  tick_timer_service_unsubscribe();
  connection_service_unsubscribe();
  window_destroy(s_window);
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
