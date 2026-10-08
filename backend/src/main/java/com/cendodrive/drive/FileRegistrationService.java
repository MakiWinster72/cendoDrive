package com.cendodrive.drive;

import com.cendodrive.drive.DriveDtos.FileResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileRegistrationService {
    private final DriveFileRepository files;

    public FileRegistrationService(DriveFileRepository files) {
        this.files = files;
    }

    @Transactional
    public FileResponse register(Long ownerId, Long parentId, String name, long size, String storageKey) {
        DriveFile file = DriveFile.uploaded(ownerId, parentId, name, size, storageKey);
        return FileResponse.from(files.saveAndFlush(file));
    }
}
