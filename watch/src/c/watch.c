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
static AppState s_state = APP_STATE_NO_PHONE;

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
}

static void prv_send_command(Command command) {
  DictionaryIterator *iter;
  AppMessageResult result = app_message_outbox_begin(&iter);
  if (result != APP_MSG_OK) {
    prv_set_state(APP_STATE_NO_PHONE);
    return;
  }

  dict_write_int(iter, MESSAGE_KEY_COMMAND, &command, sizeof(int32_t), true);
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

  s_status_layer = text_layer_create(GRect(0, 72, bounds.size.w, 20));
  text_layer_set_text_alignment(s_status_layer, GTextAlignmentCenter);
  text_layer_set_font(s_status_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  layer_add_child(window_layer, text_layer_get_layer(s_status_layer));

  prv_set_state(connection_service_peek_pebble_app_connection() ? APP_STATE_IDLE : APP_STATE_NO_PHONE);
}

static void prv_window_unload(Window *window) {
  text_layer_destroy(s_status_layer);
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
  connection_service_unsubscribe();
  window_destroy(s_window);
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
