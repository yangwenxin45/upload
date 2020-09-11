package com.example.demo;

import com.example.demo.param.MultipartFileParam;
import com.example.demo.util.FileMD5Util;
import com.example.demo.vo.ResultStatus;
import com.example.demo.vo.ResultVo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.List;

@Slf4j
@Getter
@Setter
@ToString
@RestController
@RequestMapping("/index")
public class IndexController {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RequestMapping(value = "/checkFileMd5", method = RequestMethod.POST)
    public ResultVo<Object> checkFileMd5(String md5) throws Exception {
        Object processingObj = stringRedisTemplate.opsForHash().get("FILE_UPLOAD_STATUS", md5);
        if (processingObj == null) {
            return new ResultVo<>(ResultStatus.NO_HAVE);
        }
        String processingStr = processingObj.toString();
        boolean processing = Boolean.parseBoolean(processingStr);
        String value = stringRedisTemplate.opsForValue().get("FILE_MD5:" + md5);
        if (processing) {
            return new ResultVo<>(ResultStatus.IS_HAVE, value);
        } else {
            File confFile = new File(value);
            byte[] completeList = FileUtils.readFileToByteArray(confFile);
            List<String> missChunkList = new LinkedList<>();
            for (int i=0; i<completeList.length; i++) {
                if (completeList[i] != Byte.MAX_VALUE) {
                    missChunkList.add(i + "");
                }
            }
            return new ResultVo<>(ResultStatus.ING_HAVE, missChunkList);
        }
    }

    @RequestMapping(value = "/fileUpload", method = RequestMethod.POST)
    public ResponseEntity fileUpload(MultipartFileParam param, HttpServletRequest request) throws Exception {
        boolean isMultipart = ServletFileUpload.isMultipartContent(request);
        if (isMultipart) {
            String fileName = param.getName();
            String uploadDirPath = "/Users/yang/file/upload/" + param.getMd5();
            String tempFileName = fileName + "_tmp";
            File tmpDir = new File(uploadDirPath);
            File tmpFile = new File(uploadDirPath, tempFileName);
            if (!tmpDir.exists()) {
                tmpDir.mkdirs();
            }
            RandomAccessFile tempRaf = new RandomAccessFile(tmpFile, "rw");
            FileChannel fileChannel = tempRaf.getChannel();
            // 写入分片数据
            long chunkSize = 8 * 1024 * 1024L;
            long offset = chunkSize * param.getChunk();
            byte[] fileData = param.getFile().getBytes();
            MappedByteBuffer mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, offset, fileData.length);
            mappedByteBuffer.put(fileData);
            FileMD5Util.freedMappedByteBuffer(mappedByteBuffer);
            fileChannel.close();
            boolean isOK = checkAndSetUploadProgress(param, uploadDirPath);
            if (isOK) {
                boolean flag = renameFile(tmpFile, fileName);
                log.info("upload complete !!" + flag + " name=" + fileName);
            }
        }
        return ResponseEntity.ok().body("上传成功");
    }

    private boolean checkAndSetUploadProgress(MultipartFileParam param, String uploadDirPath) throws Exception {
        String fileName = param.getName();
        File confFile = new File(uploadDirPath, fileName + ".conf");
        RandomAccessFile accessConfFile = new RandomAccessFile(confFile, "rw");
        accessConfFile.setLength(param.getChunks());
        accessConfFile.seek(param.getChunk());
        accessConfFile.write(Byte.MAX_VALUE);

        // completeList检查是否全部上传完成
        byte[] completeList = FileUtils.readFileToByteArray(confFile);
        byte isComplete = Byte.MAX_VALUE;
        for (int i=0; i<completeList.length && isComplete == Byte.MAX_VALUE; i++) {
            isComplete = (byte) (isComplete & completeList[i]);
        }
        accessConfFile.close();
        if (isComplete == Byte.MAX_VALUE) {
            stringRedisTemplate.opsForHash().put("FILE_UPLOAD_STATUS", param.getMd5(), "true");
            stringRedisTemplate.opsForValue().set("FILE_MD5:" + param.getMd5(), uploadDirPath + "/" + fileName);
            return true;
        } else {
            if (!stringRedisTemplate.opsForHash().hasKey("FILE_UPLOAD_STATUS", param.getMd5())) {
                stringRedisTemplate.opsForHash().put("FILE_UPLOAD_STATUS", param.getMd5(), "false");
            }
            if (!stringRedisTemplate.hasKey("FILE_MD5" + param.getMd5())) {
                stringRedisTemplate.opsForValue().set("FILE_MD5:" + param.getMd5(), uploadDirPath + "/" + fileName + ".conf");
            }
            return false;
        }
    }

    public boolean renameFile(File toBeRenamed, String toFileNewName) {
        if (!toBeRenamed.exists() || toBeRenamed.isDirectory()) {
            log.info("File does not exist: " + toBeRenamed.getName());
            return false;
        }
        String p = toBeRenamed.getParent();
        File newFile = new File(p + File.separatorChar + toFileNewName);
        return toBeRenamed.renameTo(newFile);
    }

}
