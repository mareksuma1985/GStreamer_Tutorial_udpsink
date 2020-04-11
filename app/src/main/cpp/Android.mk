LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_CFLAGS := -DGST_USE_UNSTABLE_API
LOCAL_MODULE    := android_camera
LOCAL_SRC_FILES := android_camera.c
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -landroid -llog
include $(BUILD_SHARED_LIBRARY)

APP_PLATFORM := android-6

ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID must be defined in /etc/profile!)
endif

ifeq ($(TARGET_ARCH_ABI),armeabi)
GSTREAMER_ROOT        := $(GSTREAMER_ROOT_ANDROID)/arm
else ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
GSTREAMER_ROOT        := $(GSTREAMER_ROOT_ANDROID)/armv7
else ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
GSTREAMER_ROOT        := $(GSTREAMER_ROOT_ANDROID)/arm64
else
$(error Target arch ABI not supported: $(TARGET_ARCH_ABI))
endif

GSTREAMER_NDK_BUILD_PATH  := $(GSTREAMER_ROOT)/share/gst-android/ndk-build
include $(GSTREAMER_NDK_BUILD_PATH)/plugins.mk
GSTREAMER_PLUGINS         := $(GSTREAMER_PLUGINS_CORE) $(GSTREAMER_PLUGINS_ENCODING) androidmedia openh264 opensles opengl $(GSTREAMER_PLUGINS_NET)
GSTREAMER_EXTRA_DEPS      := gstreamer-video-1.0 gstreamer-player-1.0 gio-2.0 glib-2.0
include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk
