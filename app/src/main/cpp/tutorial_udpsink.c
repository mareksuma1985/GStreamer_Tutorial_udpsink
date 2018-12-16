#include <string.h>
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <gst/gst.h>
#include <stdio.h>

GST_DEBUG_CATEGORY_STATIC (debug_category);
#define GST_CAT_DEFAULT debug_category

/*
 * These macros provide a way to store the native pointer to CustomData, which might be 32 or 64 bits, into
 * a jlong, which is always 64 bits, without warnings.
 */

/*#if GLIB_SIZEOF_VOID_P == 8*/
#define GET_CUSTOM_DATA(env, thiz, fieldID) (CustomData *)(*env)->GetLongField (env, thiz, fieldID)
#define SET_CUSTOM_DATA(env, thiz, fieldID, data) (*env)->SetLongField (env, thiz, fieldID, (jlong)data)
/*#else*/
/*#define GET_CUSTOM_DATA(env, thiz, fieldID) (CustomData *)(jint)(*env)->GetLongField (env, thiz, fieldID)*/
/*#define SET_CUSTOM_DATA(env, thiz, fieldID, data) (*env)->SetLongField (env, thiz, fieldID, (jlong)(jint)data)*/
/*#endif*/

/* Structure to contain all our information, so we can pass it to callbacks */
typedef struct CustomData {
    jobject app;           /* Application instance, used to call its methods. A global reference is kept. */
    GstElement *pipeline;  /* The running pipeline */
    GMainContext *context; /* GLib context used to run the main loop */
    GMainLoop *main_loop;  /* GLib main loop */
    gboolean initialized;  /* To avoid informing the UI multiple times about the initialization */
} CustomData;

/* These global variables cache values which are not changing during execution */
static pthread_t gst_app_thread;
static pthread_key_t current_jni_env;
static JavaVM *java_vm;
static jfieldID custom_data_field_id;
static jmethodID set_message_method_id;
static jmethodID on_gstreamer_initialized_method_id;

/*
 * Private methods
 */

/* Register this thread with the VM */
static JNIEnv *attach_current_thread(void) {
    JNIEnv *env;
    JavaVMAttachArgs args;

    GST_DEBUG ("Attaching thread %p", g_thread_self());
    args.version = JNI_VERSION_1_4;
    args.name = NULL;
    args.group = NULL;

    if ((*java_vm)->AttachCurrentThread(java_vm, &env, &args) < 0) {
        GST_ERROR ("Failed to attach current thread");
        return NULL;
    }

    return env;
}

/* Unregister this thread from the VM */
static void detach_current_thread(void *env) {
    GST_DEBUG ("Detaching thread %p", g_thread_self());
    (*java_vm)->DetachCurrentThread(java_vm);
}

/* Retrieve the JNI environment for this thread */
static JNIEnv *get_jni_env(void) {
    JNIEnv *env;

    if ((env = pthread_getspecific(current_jni_env)) == NULL) {
        env = attach_current_thread();
        pthread_setspecific(current_jni_env, env);
    }

    return env;
}

/* Change the content of the UI's TextView */
static void set_ui_message(const gchar *message, CustomData *data) {
    JNIEnv *env = get_jni_env();
    GST_DEBUG ("Setting message to: %s", message);
    jstring jmessage = (*env)->NewStringUTF(env, message);
    (*env)->CallVoidMethod(env, data->app, set_message_method_id, jmessage);
    if ((*env)->ExceptionCheck(env)) {
        GST_ERROR ("Failed to call Java method");
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, jmessage);
}

/* Retrieve errors from the bus and show them on the UI */
static void error_cb(GstBus *bus, GstMessage *msg, CustomData *data) {
    GError *err;
    gchar *debug_info;
    gchar *message_string;

    gst_message_parse_error(msg, &err, &debug_info);
    message_string = g_strdup_printf("Error received from element %s: %s",
                                     GST_OBJECT_NAME (msg->src), err->message);
    g_clear_error(&err);
    g_free(debug_info);
    set_ui_message(message_string, data);
    g_free(message_string);
    gst_element_set_state(data->pipeline, GST_STATE_NULL);
}

/* Notify UI about pipeline state changes */
static void state_changed_cb(GstBus *bus, GstMessage *msg, CustomData *data) {
    GstState old_state, new_state, pending_state;
    gst_message_parse_state_changed(msg, &old_state, &new_state, &pending_state);
    /* Only pay attention to messages coming from the pipeline, not its children */
    if (GST_MESSAGE_SRC (msg) == GST_OBJECT (data->pipeline)) {
        gchar *message = g_strdup_printf("State changed to %s",
                                         gst_element_state_get_name(new_state));
        set_ui_message(message, data);
        g_free(message);
    }
}

/* Check if all conditions are met to report GStreamer as initialized.
 * These conditions will change depending on the application */
static void check_initialization_complete(CustomData *data) {
    JNIEnv *env = get_jni_env();
    if (!data->initialized && data->main_loop) {
        GST_DEBUG ("Initialization complete, notifying application. main_loop:%p", data->main_loop);
        (*env)->CallVoidMethod(env, data->app, on_gstreamer_initialized_method_id);
        if ((*env)->ExceptionCheck(env)) {
            GST_ERROR ("Failed to call Java method");
            (*env)->ExceptionClear(env);
        }
        data->initialized = TRUE;
    }
}

/* Main method for the native code. This is executed on its own thread. */
static void *app_function(void *userdata) {
    JavaVMAttachArgs args;
    GstBus *bus;
    CustomData *data = (CustomData *) userdata;
    GSource *bus_source;
    GError *error = NULL;

    GST_DEBUG ("Creating pipeline in CustomData at %p", data);

    /* Create our own GLib Main Context and make it the default one */
    data->context = g_main_context_new();
    g_main_context_push_thread_default(data->context);

    /* Build pipeline */
    data->pipeline = gst_parse_launch("audiotestsrc ! audioconvert ! audioresample ! autoaudiosink", &error);

    if (error) {
        gchar *message = g_strdup_printf("Unable to build pipeline: %s", error->message);
        g_clear_error(&error);
        set_ui_message(message, data);
        g_free(message);
        return NULL;
    }

    /* Instruct the bus to emit signals for each received message, and connect to the interesting signals */
    bus = gst_element_get_bus(data->pipeline);
    bus_source = gst_bus_create_watch(bus);
    g_source_set_callback(bus_source, (GSourceFunc) gst_bus_async_signal_func, NULL, NULL);
    g_source_attach(bus_source, data->context);
    g_source_unref(bus_source);
    g_signal_connect (G_OBJECT(bus), "message::error", (GCallback) error_cb, data);
    g_signal_connect (G_OBJECT(bus), "message::state-changed", (GCallback) state_changed_cb, data);
    gst_object_unref(bus);

    /* Create a GLib Main Loop and set it to run */
    GST_DEBUG ("Entering main loop... (CustomData:%p)", data);
    data->main_loop = g_main_loop_new(data->context, FALSE);
    check_initialization_complete(data);
    g_main_loop_run(data->main_loop);
    GST_DEBUG ("Exited main loop");
    g_main_loop_unref(data->main_loop);
    data->main_loop = NULL;

    /* Free resources */
    g_main_context_pop_thread_default(data->context);
    g_main_context_unref(data->context);
    gst_element_set_state(data->pipeline, GST_STATE_NULL);
    gst_object_unref(data->pipeline);

    return NULL;
}

char nadajnik_IP_string[128];
int v4l_device_number = 0;
gboolean video_running = FALSE;

static void on_pad_added(GstElement *element, GstPad *pad, gpointer data) {
    GstPad *sinkpad;
    GstElement *encoder = (GstElement *) data;
    g_print("Dynamic pad created, linking\n");
    sinkpad = gst_element_get_static_pad(encoder, "sink");
    gst_pad_link(pad, sinkpad);
    gst_object_unref(sinkpad);
}

GstElement *pipeline;
GstElement *bin, *encoder, *udpsink;
gboolean first_time_video = TRUE;
gboolean first_time_audio = TRUE;

int video_start(char arg[]) {
    char remote_IP_string[128];

    /* executed only on first camera activation */
    if (first_time_video) {
        /* videotestsrc */
        pipeline = gst_pipeline_new("pipeline");
        g_assert(pipeline);

        bin = gst_element_factory_make("videotestsrc", "source");
        if (!bin) { GST_DEBUG ("NOGO: bin is null!"); }
        g_assert(bin);

        encoder = gst_element_factory_make("openh264enc", "encoder");
        if (!encoder) { GST_DEBUG ("NOGO: encoder is null!"); }
        g_assert(encoder);

        udpsink = gst_element_factory_make("udpsink", "sink");
        if (!udpsink) { GST_DEBUG ("NOGO: UDP sink is null!"); }
        g_assert(udpsink);

        gst_bin_add(GST_BIN(pipeline), bin);
        gst_bin_add(GST_BIN(pipeline), encoder);
        gst_bin_add(GST_BIN(pipeline), udpsink);

        if (!gst_element_link(bin, encoder)) {
            GST_DEBUG ("NOGO: Failed to link source (camerabin) with encoder!");
            return -1;
        } else {
            GST_DEBUG ("GO: Linked source (camerabin) with encoder.");
        }

        if (!gst_element_link(encoder, udpsink)) {
            g_print("NOGO: Failed to link encoder with UDP sink!\n");
            return -1;
        } else {
            g_print("GO: Linked encoder with UDP sink.\n");
        }
        if (first_time_video == TRUE) { first_time_video = FALSE; }
    }

    g_object_set(G_OBJECT(udpsink), "port", 5000, NULL);
    /* sets the destination port */

    sprintf(remote_IP_string, "%d.%d.%d.%d", arg[0], arg[1], arg[2], arg[3]);

    g_object_set(G_OBJECT(udpsink), "host", remote_IP_string, NULL);
    /* sets the destination IP address */

    if (gst_element_set_state(GST_ELEMENT(pipeline), GST_STATE_PLAYING)) {
        g_print("GO: Video pipeline state set to playing.\n");
    } else {
        g_print("NOGO: Failed to start up video pipeline!\n");
        return -1;
    }
    video_running = TRUE;
    return 0;
}

/* GstStateChangeReturn ret_audio; */
GstElement *pipeline_audio;
GstElement *alsasrc, *converter, *speexenc, *tcpserversink;

int audio_start() {
    if (first_time_audio == TRUE) {
        pipeline_audio = gst_pipeline_new("pipeline-audio");
        alsasrc = gst_element_factory_make("alsasrc", "alsa-source");
        converter = gst_element_factory_make("audioconvert", "audio-convert");
        speexenc = gst_element_factory_make("speexenc", "audio-encoder");
        tcpserversink = gst_element_factory_make("tcpserversink", "tcp-server-sink");

        if (!tcpserversink) {
            g_print("Output could not be found - check your install!\n");
        }

        g_assert (pipeline_audio != NULL);
        g_assert (alsasrc != NULL);
        g_assert (converter != NULL);
        g_assert (speexenc != NULL);
        g_assert (tcpserversink != NULL);

        gst_bin_add(GST_BIN(pipeline_audio), alsasrc);
        gst_bin_add(GST_BIN(pipeline_audio), converter);
        gst_bin_add(GST_BIN(pipeline_audio), speexenc);
        gst_bin_add(GST_BIN(pipeline_audio), tcpserversink);

        if (!gst_element_link(alsasrc, converter)) {
            g_print("NOGO: Failed to link audio source with converter!\n");
            return -1;
        } else {
            g_print("GO: Linked audio source with converter.\n");
        }

        if (!gst_element_link(converter, speexenc)) {
            g_print("NOGO: Failed to link converter with speexenc!\n");
            return -1;
        } else {
            g_print("GO: Linked converter with speexenc.\n");
        }

        if (!gst_element_link(speexenc, tcpserversink)) {
            g_print("NOGO: Failed to link speexenc with tcpserversink!\n");
            return -1;
        } else {
            g_print("GO: Linked speexenc with tcpserversink.\n");
        }
        first_time_video = FALSE;
    }

    g_object_set(G_OBJECT(tcpserversink), "host", nadajnik_IP_string, NULL);
    g_object_set(G_OBJECT(tcpserversink), "port", 5001, NULL);

    if (gst_element_set_state(GST_ELEMENT(pipeline_audio), GST_STATE_PLAYING)) {
        g_print("GO: Audio pipeline state set to playing.\n");
        return 1;
    } else {
        g_print("NOGO: Failed to start up audio pipeline!\n");
        return -1;
    }
}

int video_stop() {
    gst_element_set_state(pipeline, GST_STATE_PAUSED);
    g_print("Video pipeline: paused\n");
    gst_element_set_state(pipeline, GST_STATE_NULL);
    g_print("Video pipeline: null\n");
    video_running = FALSE;
    return 1;
}

int audio_stop() {
    gst_element_set_state(pipeline_audio, GST_STATE_PAUSED);
    g_print("Audio pipeline: paused\n");
    gst_element_set_state(pipeline_audio, GST_STATE_NULL);
    g_print("Audio pipeline: null\n");
    return 1;
}

/*
 * Java Bindings
 */

/* Instruct the native code to create its internal data structure, pipeline and thread */
static void gst_native_init(JNIEnv *env, jobject thiz) {
    CustomData *data = g_new0 (CustomData, 1);
    SET_CUSTOM_DATA (env, thiz, custom_data_field_id, data);
    GST_DEBUG_CATEGORY_INIT (debug_category, "tutorial", 0, "Android GStreamer tutorial");
    gst_debug_set_threshold_for_name("tutorial", GST_LEVEL_DEBUG);
    GST_DEBUG ("Created CustomData at %p", data);
    data->app = (*env)->NewGlobalRef(env, thiz);
    GST_DEBUG ("Created GlobalRef for app object at %p", data->app);
    pthread_create(&gst_app_thread, NULL, &app_function, data);
}

/* Quit the main loop, remove the native thread and free resources */
static void gst_native_finalize(JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    GST_DEBUG ("Quitting main loop...");
    g_main_loop_quit(data->main_loop);
    GST_DEBUG ("Waiting for thread to finish...");
    pthread_join(gst_app_thread, NULL);
    GST_DEBUG ("Deleting GlobalRef for app object at %p", data->app);
    (*env)->DeleteGlobalRef(env, data->app);
    GST_DEBUG ("Freeing CustomData at %p", data);
    g_free(data);
    SET_CUSTOM_DATA (env, thiz, custom_data_field_id, NULL);
    GST_DEBUG ("Done finalizing");
}

/* Set pipeline to PLAYING state */
static void
gst_native_stream_start(JNIEnv *env, jobject thiz, char byte0, char byte1, char byte2, char byte3) {
    /*
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    GST_DEBUG ("Setting state to PLAYING");
    gst_element_set_state(data->pipeline, GST_STATE_PLAYING);
    */
    unsigned char ip[4] = {byte0 + 128, byte1 + 128, byte2 + 128, byte3 + 128};
    g_print("receiver IP: %d.%d.%d.%d\n", ip[0], ip[1], ip[2], ip[3]);
    g_print("stream_start: %s\n", video_start(ip) > 0 ? "OK" : "NOT OK");
    GST_DEBUG ("my pipeline streaming started");
}

/* Set pipeline to PAUSED state */
static void gst_native_stream_stop(JNIEnv *env, jobject thiz) {
    /*
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    GST_DEBUG ("Setting state to PAUSED");
    gst_element_set_state(data->pipeline, GST_STATE_PAUSED);
    */
    g_print("stream_stop: %s\n", video_stop() > 0 ? "OK" : "NOT OK");
    GST_DEBUG ("my pipeline streaming stopped");
}

/* Static class initializer: retrieve method and field IDs */
static jboolean gst_native_class_init(JNIEnv *env, jclass klass) {
    custom_data_field_id = (*env)->GetFieldID(env, klass, "native_custom_data", "J");
    set_message_method_id = (*env)->GetMethodID(env, klass, "setMessage", "(Ljava/lang/String;)V");
    on_gstreamer_initialized_method_id = (*env)->GetMethodID(env, klass, "onGStreamerInitialized",
                                                             "()V");

    if (!custom_data_field_id || !set_message_method_id || !on_gstreamer_initialized_method_id) {
        /*
         * We emit this message through the Android log instead of the GStreamer log because the later
         * has not been initialized yet.
         */
        __android_log_print(ANDROID_LOG_ERROR, "tutorial",
                            "The calling class does not implement all necessary interface methods");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* List of implemented native methods, you define accepted arguments and return type in the second column */
static JNINativeMethod native_methods[] = {
        {"nativeInit",        "()V",     (void *) gst_native_init},
        {"nativeFinalize",    "()V",     (void *) gst_native_finalize},
        {"nativeStreamStart", "(CCCC)V", (void *) gst_native_stream_start},
        {"nativeStreamStop",  "()V",     (void *) gst_native_stream_stop},
        {"nativeClassInit",   "()Z",     (void *) gst_native_class_init},
};

/* Library initializer */
jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;

    java_vm = vm;

    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_4) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "tutorial_udpsink", "Could not retrieve JNIEnv");
        return 0;
    }
    jclass klass = (*env)->FindClass(env, "pl/bezzalogowe/gstreamer/MainActivity");
    (*env)->RegisterNatives(env, klass, native_methods, G_N_ELEMENTS(native_methods));

    pthread_key_create(&current_jni_env, detach_current_thread);

    return JNI_VERSION_1_4;
}
