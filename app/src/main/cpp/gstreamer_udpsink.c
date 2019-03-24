#include <stdio.h>
#include <string.h>
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <gst/gst.h>
#include <gst/video/videooverlay.h>


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

/*
    //data->pipeline = gst_parse_launch("audiotestsrc ! audioconvert ! audioresample ! autoaudiosink", &error);
    //data->pipeline = gst_parse_launch("audiotestsrc ! audioconvert ! audioresample ! audio/x-raw,format=S16LE,channels=1,rate=8000 ! udpsink host=192.168.0.196 port=5001", &error);
    data->pipeline = gst_parse_launch("openslessrc ! audioconvert ! audioresample ! audio/x-raw,format=S16LE,channels=1,rate=16000 ! flacenc name=enc quality=6 ! udpsink host=192.168.0.196 port=5001", &error);

    if (error) {
        gchar *message = g_strdup_printf("Unable to build pipeline: %s", error->message);
        g_clear_error(&error);
        set_ui_message(message, data);
        g_free(message);
        return NULL;
    }


    bus = gst_element_get_bus(data->pipeline);
    bus_source = gst_bus_create_watch(bus);
    g_source_set_callback(bus_source, (GSourceFunc) gst_bus_async_signal_func, NULL, NULL);
    g_source_attach(bus_source, data->context);
    g_source_unref(bus_source);
    g_signal_connect (G_OBJECT(bus), "message::error", (GCallback) error_cb, data);
    g_signal_connect (G_OBJECT(bus), "message::state-changed", (GCallback) state_changed_cb, data);
    gst_object_unref(bus);


    GST_DEBUG ("Entering main loop... (CustomData:%p)", data);
    data->main_loop = g_main_loop_new(data->context, FALSE);
    check_initialization_complete(data);
    g_main_loop_run(data->main_loop);
    GST_DEBUG ("Exited main loop");
    g_main_loop_unref(data->main_loop);
    data->main_loop = NULL;

    g_main_context_pop_thread_default(data->context);
    g_main_context_unref(data->context);
    gst_element_set_state(data->pipeline, GST_STATE_NULL);
    gst_object_unref(data->pipeline);
*/

    return NULL;
}

/** telerobot_server */
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
GstElement *camera, *queue, *capsfilter, *videoconvert, *encoder, *udpsink;

gboolean first_time_video = TRUE;

int video_start(int size[], int bitrate, char arg[], int port) {
    char remote_IP_string[128];
    sprintf(remote_IP_string, "%d.%d.%d.%d", arg[0], arg[1], arg[2], arg[3]);

    g_print("Receiver IP: %d.%d.%d.%d, port: %d\n", arg[0], arg[1], arg[2], arg[3], port);
    g_print("bitrate: %d, width: %d, height: %d\n", bitrate, size[0], size[1]);

    char v4l_device_path[11];
    /* "/dev/videoX" is 11 characters long */

    /* executed only on first camera activation */
    if (first_time_video) {
        /* videotestsrc */
        /*
        bin = gst_element_factory_make("videotestsrc", "source");
        if (!bin) { GST_DEBUG ("NOGO: bin is null!"); }
        g_assert(bin);
        */

        /* ahcsrc */
        camera = gst_element_factory_make("ahcsrc", "ahcsrc");
        if (!camera) { GST_DEBUG ("NOGO: camera is null!"); }
        g_assert(camera);

        pipeline = gst_pipeline_new("pipeline");
        g_assert(pipeline);

        queue = gst_element_factory_make("queue", "srcqueue");
        g_assert(queue);

        /** The element does not modify data as such, but can enforce limitations on the data format.  */
        capsfilter = gst_element_factory_make("capsfilter", NULL);
        if (!capsfilter) { GST_DEBUG ("capsfilter is null: NOGO!"); }
        g_assert(capsfilter);

        // setting caps filter moved

        videoconvert = gst_element_factory_make("videoconvert", NULL);
        if (!videoconvert) { GST_DEBUG ("videoconvert is null: NOGO!"); }
        g_assert(videoconvert);

        encoder = gst_element_factory_make("openh264enc", "encoder");
        if (!encoder) { GST_DEBUG ("encoder is null: NOGO!"); }
        g_assert(encoder);

        // setting bitrate moved

        udpsink = gst_element_factory_make("udpsink", "sink");
        if (!udpsink) { GST_DEBUG ("UDP sink is null: NOGO!"); }
        g_assert(udpsink);

        gst_bin_add_many(GST_BIN(pipeline), camera, queue, capsfilter, videoconvert, encoder,
                         udpsink, NULL);

        if (!gst_element_link(camera, queue)) {
            GST_DEBUG ("Failed to link ahcsrc camera with queue!\n");
            return -1;
        } else {
            g_print("Linked ahcsrc camera with queue: OK\n");
        }

        if (!gst_element_link(queue, capsfilter)) {
            GST_DEBUG ("Failed to link queue with capsfilter!\n");
            return -1;
        } else {
            g_print("Linked queue with capsfilter: OK\n");
        }

        if (!gst_element_link(capsfilter, videoconvert)) {
            GST_DEBUG ("Failed to link capsfilter with converter!\n");
            return -1;
        } else {
            g_print("Linked capsfilter with converter: OK\n");
        }

        if (!gst_element_link(videoconvert, encoder)) {
            GST_DEBUG ("Failed to link converter with encoder!\n");
            return -1;
        } else {
            g_print("Linked converter with encoder: OK\n");
        }

        if (!gst_element_link(encoder, udpsink)) {
            GST_DEBUG ("Failed to link encoder with UDP sink!\n");
            return -1;
        } else {
            g_print("Linked encoder with UDP sink: OK\n");
        }
        first_time_video = FALSE;
    }

    GstCaps *new_caps;
    new_caps = gst_caps_new_simple("video/x-raw", "width", G_TYPE_INT, size[0], "height",
                                   G_TYPE_INT, size[1], NULL);
    g_object_set(capsfilter, "caps", new_caps, NULL);
    gst_caps_unref(new_caps);

    g_object_set(encoder, "bitrate", bitrate, NULL);

    g_object_set(G_OBJECT(udpsink), "port", port, NULL);
    /* sets the destination port */

    g_object_set(G_OBJECT(udpsink), "host", remote_IP_string, NULL);
    /* sets the destination IP address */

    if (gst_element_set_state(GST_ELEMENT(pipeline), GST_STATE_PLAYING)) {
        g_print("Video pipeline state set to playing: OK\n");
    } else {
        GST_DEBUG ("Failed to start up video pipeline!\n");
        return -1;
    }

    video_running = TRUE;
    return 0;
}

int video_stop() {
    gst_element_set_state(pipeline, GST_STATE_PAUSED);
    g_print("Video pipeline: paused\n");
    gst_element_set_state(pipeline, GST_STATE_NULL);
    g_print("Video pipeline: null\n");
    /* gst_object_unref (GST_OBJECT (pipeline));
     g_print ("Deleting video pipeline\n"); */
    video_running = FALSE;
    return 1;
}

GstElement *pipeline_audio;
GstElement *audiosource, *audioqueue, *audiocapsfilter, *audioconvert, *audioresample, *audioudpsink;
GstElement *audioencoder;

int audio_start(int bitrate, char arg[], int port) {
    char remote_IP_string[128];
    sprintf(remote_IP_string, "%d.%d.%d.%d", arg[0], arg[1], arg[2], arg[3]);
/*
    if (first_time_audio == TRUE) {
    first_time_audio = FALSE;
    }
*/
    pipeline_audio = gst_pipeline_new("pipeline-audio");
    g_assert (pipeline_audio != NULL);

    audiosource = gst_element_factory_make("openslessrc", NULL);
    if (!audiosource) { GST_DEBUG ("openslessrc is null: NOGO!"); }

/** failover */
/*
        if (audiosource == NULL) {
            audiosource = gst_element_factory_make("audiotestsrc", NULL);
        }
        if (!audiosource) { GST_DEBUG ("audiotestsrc is null: NOGO!"); }
        g_assert (audiosource != NULL);
*/
    audioqueue = gst_element_factory_make("queue", NULL);
    g_assert(audioqueue);

    audiocapsfilter = gst_element_factory_make("capsfilter", NULL);
    if (!audiocapsfilter) { GST_DEBUG ("audiocapsfilter is null: NOGO!"); }
    g_assert(audiocapsfilter);

    GstCaps *new_caps;
    new_caps = gst_caps_new_simple("audio/x-raw", "format", G_TYPE_STRING, "S16LE", "channels",
                                   G_TYPE_INT, 1, "rate", G_TYPE_INT, bitrate, NULL);
    g_object_set(audiocapsfilter, "caps", new_caps, NULL);
    g_print("Audio bitrate: %d\n", bitrate);
    gst_caps_unref(new_caps);

    audioconvert = gst_element_factory_make("audioconvert", NULL);
    g_assert (audioconvert != NULL);

    audioresample = gst_element_factory_make("audioresample", NULL);
    g_assert (audioresample != NULL);

    audioudpsink = gst_element_factory_make("udpsink", NULL);
    if (!audioudpsink) { GST_DEBUG ("audio UDP sink is null: NOGO!"); }
    g_assert(audioudpsink);

    gst_bin_add_many(GST_BIN(pipeline_audio), audiosource, audioqueue, audiocapsfilter,
                     audioconvert, audioresample, audioudpsink, NULL);

    if (!gst_element_link_many(audiosource, audioqueue, audiocapsfilter, audioconvert,
                               audioresample, audioudpsink, NULL)) {
        GST_DEBUG ("Failed to link audio pipeline elements!\n");
    }

    g_object_set(G_OBJECT(audioudpsink), "host", remote_IP_string, NULL);
    g_object_set(G_OBJECT(audioudpsink), "port", port, NULL);

    g_object_get(audioudpsink, "port", &port, NULL);
    g_print("Audio port: %d\n", port);

    if (gst_element_set_state(GST_ELEMENT(pipeline_audio), GST_STATE_PLAYING)) {
        g_print("Audio pipeline state set to playing: OK\n");

        return 1;
    } else {
        GST_DEBUG ("Failed to start up audio pipeline!\n");
        return -1;
    }
}

int audio_flac_start(int bitrate, char arg[], int port) {
    char remote_IP_string[128];
    sprintf(remote_IP_string, "%d.%d.%d.%d", arg[0], arg[1], arg[2], arg[3]);
/*
    if (first_time_audio == TRUE) {
        first_time_audio = FALSE;
    }
*/
    pipeline_audio = gst_pipeline_new("pipeline-audio");
    g_assert (pipeline_audio != NULL);

    audiosource = gst_element_factory_make("openslessrc", NULL);
    if (!audiosource) { GST_DEBUG ("openslessrc is null: NOGO!"); }

    audioqueue = gst_element_factory_make("queue", NULL);
    g_assert(audioqueue);

    audiocapsfilter = gst_element_factory_make("capsfilter", NULL);
    if (!audiocapsfilter) { GST_DEBUG ("audiocapsfilter is null: NOGO!"); }
    g_assert(audiocapsfilter);

    GstCaps *new_caps;
    new_caps = gst_caps_new_simple("audio/x-raw", "format", G_TYPE_STRING, "S16LE", "channels",
                                   G_TYPE_INT, 1, "rate", G_TYPE_INT, bitrate, NULL);
    g_object_set(audiocapsfilter, "caps", new_caps, NULL);
    g_print("Audio bitrate: %d (FLAC)\n", bitrate);
    gst_caps_unref(new_caps);

    audioconvert = gst_element_factory_make("audioconvert", NULL);
    g_assert (audioconvert != NULL);

    audioresample = gst_element_factory_make("audioresample", NULL);
    g_assert (audioresample != NULL);

    audioencoder = gst_element_factory_make("flacenc", NULL);
    g_assert (audioencoder != NULL);

    g_object_set(G_OBJECT(audioencoder), "name", "enc", NULL);
    g_object_set(G_OBJECT(audioencoder), "quality", 6, NULL);

    audioudpsink = gst_element_factory_make("udpsink", NULL);
    if (!audioudpsink) { GST_DEBUG ("audio UDP sink is null: NOGO!"); }
    g_assert(audioudpsink);

    gst_bin_add_many(GST_BIN(pipeline_audio), audiosource, audioqueue, audiocapsfilter,
                     audioconvert, audioresample, audioencoder, audioudpsink, NULL);

    if (!gst_element_link_many(audiosource, audioqueue, audiocapsfilter, audioconvert,
                               audioresample, audioencoder, audioudpsink, NULL)) {
        GST_DEBUG ("Failed to link audio pipeline elements!\n");
    }

    g_object_set(G_OBJECT(audioudpsink), "host", remote_IP_string, NULL);
    g_object_set(G_OBJECT(audioudpsink), "port", port, NULL);

    g_object_get(audioudpsink, "port", &port, NULL);
    g_print("Audio port: %d\n", port);

    if (gst_element_set_state(GST_ELEMENT(pipeline_audio), GST_STATE_PLAYING)) {
        g_print("Audio FLAC pipeline state set to playing: OK\n");

        return 1;
    } else {
        GST_DEBUG ("Failed to start up audio FLAC pipeline!\n");
        return -1;
    }
}

int audio_stop() {
    gst_element_set_state(pipeline_audio, GST_STATE_PAUSED);
    g_print("Audio pipeline: paused\n");
    gst_element_set_state(pipeline_audio, GST_STATE_NULL);
    g_print("Audio pipeline: null\n");

    audiosource = NULL;
    audioqueue = NULL;
    audiocapsfilter = NULL;
    audioconvert = NULL;
    audioresample = NULL;
    audioudpsink = NULL;
    audioencoder = NULL;

    return 1;
}

/*
 * Java Bindings
 */

/* Instruct the native code to create its internal data structure, pipeline and thread */
static void gst_native_init(JNIEnv *env, jobject thiz) {
    CustomData *data = g_new0 (CustomData, 1);
    SET_CUSTOM_DATA (env, thiz, custom_data_field_id, data);
    GST_DEBUG_CATEGORY_INIT (debug_category, "udpsink", 0, "Android GStreamer Webcam");
    gst_debug_set_threshold_for_name("udpsink", GST_LEVEL_DEBUG);
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
static void gst_native_play(JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    GST_DEBUG ("Setting state to PLAYING");
    gst_element_set_state(data->pipeline, GST_STATE_PLAYING);
}

/* Set pipeline to PAUSED state */
static void gst_native_pause(JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    GST_DEBUG ("Setting state to PAUSED");
    gst_element_set_state(data->pipeline, GST_STATE_PAUSED);
}

/* Set pipeline to PLAYING state */
static void
gst_native_stream_start(JNIEnv *env, jobject thiz, char resolution_code, int bitrate, char byte0,
                        char byte1, char byte2, char byte3, int port) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;

    unsigned char ip[4] = {byte0 + 128, byte1 + 128, byte2 + 128, byte3 + 128};

    /** the values must match the ones in settings */
    int size[2] = {640, 480};
    switch (resolution_code) {
        case 0:
            size[0] = 176;
            size[1] = 144;
            break;
        case 1:
            size[0] = 320;
            size[1] = 240;
            break;
        case 2:
            size[0] = 352;
            size[1] = 288;
            break;
        case 4:
            size[0] = 720;
            size[1] = 480;
            break;
        case 5:
            size[0] = 800;
            size[1] = 480;
            break;
        case 6:
            size[0] = 960;
            size[1] = 720;
            break;
        case 7:
            size[0] = 1280;
            size[1] = 720;
            break;
        case 8:
            size[0] = 1440;
            size[1] = 1080;
            break;
        case 9:
            size[0] = 1920;
            size[1] = 1080;
            break;
        default:
            break;
    }

    g_print("Video stream start: %s\n",
            video_start(size, bitrate, ip, port) != -1 ? "OK" : "NOT OK");

    /** sends feedback */
    gchar *message = g_strdup_printf("Video playing");
    set_ui_message(message, data);
}

/* Set pipeline to PAUSED state */
static void gst_native_stream_stop(JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;

    g_print("Video stream_stop: %s\n", video_stop() != -1 ? "OK" : "NOT OK");

    /** sends feedback */
    gchar *message = g_strdup_printf("Video stopped");
    set_ui_message(message, data);
}

/** audio */
static void
gst_native_stream_start_audio(JNIEnv *env, jobject thiz, jboolean flac, int bitrate, char byte0,
                              char byte1, char byte2, char byte3, int port) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;

    unsigned char ip[4] = {byte0 + 128, byte1 + 128, byte2 + 128, byte3 + 128};

    if (flac) {
        g_print("Audio FLAC stream start: %s\n",
                audio_flac_start(bitrate, ip, port) != -1 ? "OK" : "NOT OK");
    }
    else {
        g_print("Audio stream start: %s\n", audio_start(bitrate, ip, port) != -1 ? "OK" : "NOT OK");
    }

/** sends feedback */
    gchar *message = g_strdup_printf("Audio playing");
    set_ui_message(message, data);
}

static void gst_native_stream_stop_audio(JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;

    /** sends feedback */
    gchar *message = g_strdup_printf("Audio stopped");
    set_ui_message(message, data);
    /* pipeline is common so this function is also common for non-flac and flac */
    audio_stop();
}

/* Static class initializer: retrieve method and field IDs */
static jboolean gst_native_class_init(JNIEnv *env, jclass klass) {
    custom_data_field_id = (*env)->GetFieldID(env, klass, "native_custom_data", "J");
    set_message_method_id = (*env)->GetMethodID(env, klass, "setMessage", "(Ljava/lang/String;)V");
    on_gstreamer_initialized_method_id = (*env)->GetMethodID(env, klass, "onGStreamerInitialized",
                                                             "()V");

    if (!custom_data_field_id || !set_message_method_id || !on_gstreamer_initialized_method_id) {
        /* We emit this message through the Android log instead of the GStreamer log because the later
         * has not been initialized yet.
         */
        __android_log_print(ANDROID_LOG_ERROR, "udpsink",
                            "The calling class does not implement all necessary interface methods");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* List of implemented native methods, you define accepted arguments and return type in the second column */
static JNINativeMethod native_methods[] = {
        {"nativeInit",             "()V",        (void *) gst_native_init},
        {"nativeFinalize",         "()V",        (void *) gst_native_finalize},
/*
        { "nativePlay", "()V", (void *) gst_native_stream_start_audio},
        { "nativePause", "()V", (void *) gst_native_stream_stop_audio},
*/
        {"nativeStreamStart",      "(CICCCCI)V", (void *) gst_native_stream_start},
        {"nativeStreamStop",       "()V",        (void *) gst_native_stream_stop},

        {"nativeStreamStartAudio", "(ZICCCCI)V", (void *) gst_native_stream_start_audio},
        {"nativeStreamStopAudio",  "()V",        (void *) gst_native_stream_stop_audio},

        {"nativeClassInit",        "()Z",        (void *) gst_native_class_init},
};

/* Library initializer */
jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;

    java_vm = vm;

    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_4) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "GStreamer_Webcam", "Could not retrieve JNIEnv");
        return 0;
    }
    jclass klass = (*env)->FindClass(env, "pl/bezzalogowe/udpsink/MainActivity");
    (*env)->RegisterNatives(env, klass, native_methods, G_N_ELEMENTS(native_methods));

    pthread_key_create(&current_jni_env, detach_current_thread);

    return JNI_VERSION_1_4;
}
