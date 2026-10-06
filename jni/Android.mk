LOCAL_PATH := $(call my-dir)

MODE = ANDROID
PLATFORMDIR = platform
include $(LOCAL_PATH)/$(PLATFORMDIR)/vars.mk

# Stub shared libraries the platform driver links against; the real ones are
# already on the camera, so these only satisfy the linker.
$(foreach lib, $(LIBS), \
    $(eval include $(CLEAR_VARS)) \
    $(eval LOCAL_MODULE := $(lib)) \
    $(eval LOCAL_SRC_FILES := $(wildcard $(addprefix $(LOCAL_PATH)/$(PLATFORMDIR)/$(DRIVERDIR)/$(lib), .c .cpp))) \
    $(eval LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(PLATFORMDIR)) \
    $(eval LOCAL_CFLAGS += $(DEFS) $(WFLAGS) -std=c11) \
    $(eval LOCAL_LDFLAGS += $(LFLAGS)) \
    $(eval include $(BUILD_SHARED_LIBRARY)) \
)

# libbettertimelapse.so — settings-store access only (read / write / attr / sync).
include $(CLEAR_VARS)
LOCAL_MODULE := bettertimelapse
LOCAL_SRC_FILES := jni.cpp $(foreach source, $(SOURCES), $(wildcard $(addprefix $(LOCAL_PATH)/$(source), .c .cpp)))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(PLATFORMDIR)
LOCAL_CFLAGS += $(DEFS) $(WFLAGS) -fvisibility=hidden
LOCAL_CONLYFLAGS += -std=c11
LOCAL_CPPFLAGS += -std=c++98 -Wno-vla -Wno-variadic-macros -fexceptions
LOCAL_LDFLAGS += -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--no-undefined
LOCAL_LDLIBS := -lgcc
LOCAL_SHARED_LIBRARIES := $(LIBS)
include $(BUILD_SHARED_LIBRARY)
