package app.fileroute.shizuku;

import android.os.ParcelFileDescriptor;

interface IPrivateDirService {
    void destroy() = 16777114;
    String listRecentFilesJson(in String[] dirPaths, long sinceEpochMs) = 1;
    boolean transferFile(String sourcePath, String targetPath, boolean copyOnly) = 2;
    String computeFileSha256(String filePath) = 3;
    boolean pathExists(String path) = 4;
    ParcelFileDescriptor openReadFd(String sourcePath) = 5;
    boolean deleteFile(String filePath) = 6;
    String diagnoseWeChatDirsJson(in String[] baseRoots) = 7;
}
