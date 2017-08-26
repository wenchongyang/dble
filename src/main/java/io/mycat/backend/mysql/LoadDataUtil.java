package io.mycat.backend.mysql;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import io.mycat.MycatServer;
import io.mycat.backend.BackendConnection;
import io.mycat.net.BackendAIOConnection;
import io.mycat.net.mysql.BinaryPacket;
import io.mycat.route.RouteResultsetNode;
import io.mycat.sqlengine.mpp.LoadData;

/**
 * Created by nange on 2015/3/31.
 */
public final class LoadDataUtil {
    private LoadDataUtil() {
    }

    public static void requestFileDataResponse(byte[] data, BackendConnection conn) {

        byte packId = data[3];
        BackendAIOConnection backendAIOConnection = (BackendAIOConnection) conn;
        RouteResultsetNode rrn = (RouteResultsetNode) conn.getAttachment();
        LoadData loadData = rrn.getLoadData();
        List<String> loadDataData = loadData.getData();
        try {
            if (loadDataData != null && loadDataData.size() > 0) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                for (int i = 0, loadDataDataSize = loadDataData.size(); i < loadDataDataSize; i++) {
                    String line = loadDataData.get(i);


                    String s = (i == loadDataDataSize - 1) ? line : line + loadData.getLineTerminatedBy();
                    byte[] bytes = s.getBytes(CharsetUtil.getJavaCharset(loadData.getCharset()));
                    bos.write(bytes);


                }

                packId = writeToBackConnection(packId, new ByteArrayInputStream(bos.toByteArray()), backendAIOConnection);

            } else {
                packId = writeToBackConnection(packId, new BufferedInputStream(new FileInputStream(loadData.getFileName())), backendAIOConnection);

            }
        } catch (IOException e) {

            throw new RuntimeException(e);
        } finally {
            //send empty packet
            byte[] empty = new byte[]{0, 0, 0, 3};
            empty[3] = ++packId;
            backendAIOConnection.write(empty);
        }


    }

    public static byte writeToBackConnection(byte packID, InputStream inputStream, BackendAIOConnection backendAIOConnection) throws IOException {
        try {
            int packSize = MycatServer.getInstance().getConfig().getSystem().getBufferPoolChunkSize() - 5;
            // int packSize = backendAIOConnection.getMaxPacketSize() / 32;
            //  int packSize=65530;
            byte[] buffer = new byte[packSize];
            int len = -1;

            while ((len = inputStream.read(buffer)) != -1) {
                byte[] temp = null;
                if (len == packSize) {
                    temp = buffer;
                } else {
                    temp = new byte[len];
                    System.arraycopy(buffer, 0, temp, 0, len);
                }
                BinaryPacket packet = new BinaryPacket();
                packet.setPacketId(++packID);
                packet.setData(temp);
                packet.write(backendAIOConnection);
            }

        } finally {
            inputStream.close();
        }


        return packID;
    }
}
