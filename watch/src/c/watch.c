#include <pebble.h>

// Values sent watch -> phone via MESSAGE_KEY_COMMAND.
typedef enum {
  COMMAND_STOP = 0,
  COMMAND_START = 1,
} Command;

// Values received phone -> watch via MESSAGE_KEY_STATUS.
typedef enum {
  STATUS_IDLE = 0,
  STATUS_RECORDING = 1,
  STATUS_ERROR = 2,
} Status;

typedef enum {
  APP_STATE_IDLE,
  APP_STATE_STARTING,
  APP_STATE_RECORDING,
  APP_STATE_STOPPING,
  APP_STATE_ERROR,
  APP_STATE_NO_PHONE,
} AppState;

static Window *s_window;
static TextLayer *s_status_layer;
static TextLayer *s_timer_layer;
static Layer *s_icon_layer;
static AppState s_state = APP_STATE_NO_PHONE;
static time_t s_recording_start_time;
static char s_timer_buffer[12];

// Draws a mic icon by default, swapping to a record (filled circle) icon while actively
// recording and a stop (filled square) icon while idle (ready to start).
static void prv_icon_layer_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  GPoint center = grect_center_point(&bounds);

  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_context_set_stroke_width(ctx, 3);

  switch (s_state) {
    case APP_STATE_RECORDING:
      graphics_fill_circle(ctx, center, 14);
      break;
    case APP_STATE_IDLE:
      graphics_fill_rect(ctx, GRect(center.x - 14, center.y - 14, 28, 28), 0, GCornerNone);
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

static void prv_update_timer_text(void) {
  int elapsed = (int)(time(NULL) - s_recording_start_time);
  if (elapsed < 0) {
    elapsed = 0;
  }
  snprintf(s_timer_buffer, sizeof(s_timer_buffer), "%02d:%02d", elapsed / 60, elapsed % 60);
  text_layer_set_text(s_timer_layer, s_timer_buffer);
}

static void prv_tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  prv_update_timer_text();
}

static void prv_set_state(AppState state) {
  s_state = state;

  const char *label;
  switch (state) {
    case APP_STATE_IDLE:      label = "Idle";        break;
    case APP_STATE_STARTING:  label = "Starting...";  break;
    case APP_STATE_RECORDING: label = "Recording";   break;
    case APP_STATE_STOPPING:  label = "Stopping...";  break;
    case APP_STATE_ERROR:     label = "Error";       break;
    case APP_STATE_NO_PHONE:  label = "No Phone";    break;
    default:                  label = "?";           break;
  }
  text_layer_set_text(s_status_layer, label);
  layer_mark_dirty(s_icon_layer);

  tick_timer_service_unsubscribe();
  if (state == APP_STATE_RECORDING) {
    s_recording_start_time = time(NULL);
    prv_update_timer_text();
    tick_timer_service_subscribe(SECOND_UNIT, prv_tick_handler);
  } else {
    text_layer_set_text(s_timer_layer, "");
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

  prv_set_state(command == COMMAND_START ? APP_STATE_STARTING : APP_STATE_STOPPING);
}

static void prv_select_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (s_state == APP_STATE_RECORDING) {
    prv_send_command(COMMAND_STOP);
  } else if (s_state == APP_STATE_IDLE || s_state == APP_STATE_ERROR) {
    prv_send_command(COMMAND_START);
  }
  // Ignore presses while STARTING/STOPPING (waiting on phone) or NO_PHONE.
}

static void prv_click_config_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click_handler);
}

static void prv_inbox_received_handler(DictionaryIterator *iterator, void *context) {
  Tuple *status_tuple = dict_find(iterator, MESSAGE_KEY_STATUS);
  if (!status_tuple) {
    return;
  }

  switch ((Status)status_tuple->value->int32) {
    case STATUS_IDLE:
      prv_set_state(APP_STATE_IDLE);
      break;
    case STATUS_RECORDING:
      prv_set_state(APP_STATE_RECORDING);
      vibes_short_pulse();
      break;
    case STATUS_ERROR:
      prv_set_state(APP_STATE_ERROR);
      vibes_double_pulse();
      break;
  }
}

static void prv_outbox_failed_handler(DictionaryIterator *iterator, AppMessageResult reason, void *context) {
  prv_set_state(APP_STATE_NO_PHONE);
}

static void prv_bluetooth_connection_handler(bool connected) {
  if (!connected) {
    prv_set_state(APP_STATE_NO_PHONE);
  } else if (s_state == APP_STATE_NO_PHONE) {
    prv_set_state(APP_STATE_IDLE);
  }
}

static void prv_window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);

  s_icon_layer = layer_create(GRect(0, 20, bounds.size.w, 46));
  layer_set_update_proc(s_icon_layer, prv_icon_layer_update_proc);
  layer_add_child(window_layer, s_icon_layer);

  s_status_layer = text_layer_create(GRect(0, 72, bounds.size.w, 20));
  text_layer_set_text_alignment(s_status_layer, GTextAlignmentCenter);
  text_layer_set_font(s_status_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  layer_add_child(window_layer, text_layer_get_layer(s_status_layer));

  s_timer_layer = text_layer_create(GRect(0, 96, bounds.size.w, 20));
  text_layer_set_text_alignment(s_timer_layer, GTextAlignmentCenter);
  text_layer_set_font(s_timer_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  layer_add_child(window_layer, text_layer_get_layer(s_timer_layer));

  prv_set_state(connection_service_peek_pebble_app_connection() ? APP_STATE_IDLE : APP_STATE_NO_PHONE);
}

static void prv_window_unload(Window *window) {
  text_layer_destroy(s_status_layer);
  text_layer_destroy(s_timer_layer);
  layer_destroy(s_icon_layer);
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
