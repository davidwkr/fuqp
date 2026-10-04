#!/system/bin/sh

rm -f /data/adb/post-fs-data.d/fuqp.sh
rm -f /data/adb/post-mount.d/fuqp.sh
rm -f /data/adb/boot-completed.d/fuqp.sh

# INFO: Only removes if dir is empty
rmdir /data/adb/post-fs-data.d
rmdir /data/adb/post-mount.d
rmdir /data/adb/boot-completed.d

true
