package com.xlk.screenrecoder;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Created by xlk on 2021/1/16.
 * @desc
 */
class Utils {
    /**
     * 根据mimeType类型查找支持的编码器
     * @param mimeType
     * @return
     */
    public static MediaCodecInfo[] findEncodersByType(String mimeType) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        List<MediaCodecInfo> infos = new ArrayList<>();
        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            try {
                MediaCodecInfo.CodecCapabilities cap = info.getCapabilitiesForType(mimeType);
                if (cap == null) continue;
            } catch (IllegalArgumentException e) {
                // unsupported
                continue;
            }
            infos.add(info);
        }
        return infos.toArray(new MediaCodecInfo[infos.size()]);
    }

}
