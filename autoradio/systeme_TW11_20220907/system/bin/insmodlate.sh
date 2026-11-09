#! /system/bin/sh

#gpio driver

#sw-keyboard
#echo usb_host > /sys/devices/platform/soc/usbc0/otg_role

#touch
#`insmod /system/vendor/modules/goodix_touch.ko`&

#sw-keystore


#bt
#echo interactive > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 
#echo 720000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq

#tvin

# setprop sys.tw.location 2 
#csi


#wifi

#`insmod /vendor/modules/8188fu.ko ifname=wlan0 if2name=p2p0`&
#`insmod /vendor/modules/8188eu.ko ifname=wlan0 if2name=p2p0`&
#TBOX
# ifconfig usb0 down
# ifconfig usb0 192.168.100.2
# ifconfig usb0 netmask 255.255.255.0
# ifconfig usb0 up