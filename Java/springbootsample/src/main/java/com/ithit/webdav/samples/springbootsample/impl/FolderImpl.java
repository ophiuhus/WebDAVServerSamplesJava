package com.ithit.webdav.samples.springbootsample.impl;

import com.ithit.webdav.server.File;
import com.ithit.webdav.server.Folder;
import com.ithit.webdav.server.Property;
import com.ithit.webdav.server.exceptions.ConflictException;
import com.ithit.webdav.server.exceptions.LockedException;
import com.ithit.webdav.server.exceptions.ServerException;
import com.ithit.webdav.server.exceptions.WebDavStatus;
import com.ithit.webdav.server.paging.OrderProperty;
import com.ithit.webdav.server.paging.PageResults;
import com.ithit.webdav.server.quota.Quota;
import com.ithit.webdav.server.resumableupload.ResumableUploadBase;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Represents a folder in the File system repository.
 */
class FolderImpl extends HierarchyItemImpl implements Folder, Quota, ResumableUploadBase {


    /**
     * Initializes a new instance of the {@link FolderImpl} class.
     *
     * @param name     Name of hierarchy item.
     * @param path     Relative to WebDAV root folder path.
     * @param created  Creation time of the hierarchy item.
     * @param modified Modification time of the hierarchy item.
     * @param engine   Instance of current {@link WebDavEngine}
     */
    private FolderImpl(String name, String path, long created, long modified,
                       WebDavEngine engine) {
        super(name, path, created, modified, engine);
    }

    /**
     * Returns folder that corresponds to path.
     *
     * @param path   Encoded path relative to WebDAV root.
     * @param engine Instance of {@link WebDavEngine}
     * @return Folder instance or null if physical folder not found in file system.
     * @throws ServerException in case of exception
     */
    static FolderImpl getFolder(String path, WebDavEngine engine) throws ServerException {
        BasicFileAttributes view = null;
        Path fullPath;
        String name = null;
        try {
            boolean root = path.equals("/");
            String pathFragment = decodeAndConvertToPath(path);
            String rootFolder = getRootFolder();
            fullPath = root ? Paths.get(rootFolder) : Paths.get(rootFolder, pathFragment);
            if (Files.exists(fullPath)) {
                name = root ? "ROOT" : Paths.get(pathFragment).getFileName().toString();
                view = Files.getFileAttributeView(fullPath, BasicFileAttributeView.class).readAttributes();
            }
            if (view == null || !view.isDirectory()) {
                return null;
            }
        } catch (IOException e) {
            throw new ServerException();
        }

        long created = view.creationTime().toMillis();
        long modified = view.lastModifiedTime().toMillis();
        return new FolderImpl(name, fixPath(path), created, modified, engine);
    }

    private static String fixPath(String path) {
        if (!Objects.equals(path.substring(path.length() - 1), "/")) {
            path += "/";
        }
        return path;
    }

    /**
     * Creates new {@link FileImpl} file with the specified name in this folder.
     *
     * @param name Name of the file to create.
     * @return Reference to created {@link File}.
     * @throws LockedException This folder was locked. Client did not provide the lock token.
     * @throws ServerException In case of an error.
     */
    // <<<< createFileImpl
    @Override
    public FileImpl createFile(String name) throws LockedException, ServerException {
        ensureHasToken();

        Path fullPath = Paths.get(this.getFullPath().toString(), name);
        if (!Files.exists(fullPath)) {
            try {
                Files.createFile(fullPath);
            } catch (IOException e) {
                throw new ServerException(e);
            }
            return FileImpl.getFile(getPath() + encode(name), getEngine());
        }
        return null;
    }
    // createFileImpl >>>>

    /**
     * Creates new {@link FolderImpl} folder with the specified name in this folder.
     *
     * @param name Name of the folder to create.
     * @throws LockedException This folder was locked. Client did not provide the lock token.
     * @throws ServerException In case of an error.
     */
    // <<<< createFolderImpl
    @Override
    public void createFolder(String name) throws LockedException,
            ServerException {
        ensureHasToken();

        Path fullPath = Paths.get(this.getFullPath().toString(), name);
        if (!Files.exists(fullPath)) {
            try {
                Files.createDirectory(fullPath);
            } catch (IOException e) {
                throw new ServerException(e);
            }
        }
    }
    // createFolderImpl >>>>

    /**
     * Gets the array of this folder's children.
     *
     * @param propNames List of properties to retrieve with the children. They will be queried by the engine later.
     * @param offset The number of items to skip before returning the remaining items.
     * @param nResults The number of items to return.
     * @param orderProps List of order properties requested by the client.
     * @return Instance of {@link PageResults} class that contains items on a requested page and total number of items in a folder.
     * @throws ServerException In case of an error.
     */
    // <<<< getChildren
    @Override
    public PageResults getChildren(List<Property> propNames, Long offset, Long nResults, List<OrderProperty> orderProps) throws ServerException {
        String decodedPath = HierarchyItemImpl.decodeAndConvertToPath(getPath());
        Path fullFolderPath = Paths.get(getRootFolder() + decodedPath);
        List<HierarchyItemImpl> children = new ArrayList<>();
        Long total = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(fullFolderPath)) {
            List<Path> paths = StreamSupport.stream(ds.spliterator(), false).collect(Collectors.toList());
            paths = sortChildren(paths, orderProps);
            for (Path p : paths) {
                String childPath = getPath() + encode(p.getFileName().toString());
                HierarchyItemImpl item = (HierarchyItemImpl) getEngine().getHierarchyItem(childPath);
                children.add(item);
            }
            total = (long) paths.size();
            if (offset != null && nResults != null)
            {
                children = children.stream().skip(offset).limit(nResults).collect(Collectors.toList());
            }
        } catch (IOException e) {
            getEngine().getLogger().logError(e.getMessage(), e);
        }
        return new PageResults(children, total);
    }
    // getChildren >>>>

    // <<<< deleteFolderImpl
    @Override
    public void delete() throws LockedException,
            ServerException {
        ensureHasToken();
        try {
            FileUtils.deleteDirectory(getFullPath().toFile());
        } catch (IOException e) {
            throw new ServerException(e);
        }
    }
    // deleteFolderImpl >>>>

    // <<<< copyToFolderImpl
    @Override
    public void copyTo(Folder folder, String destName, boolean deep)
            throws LockedException, ServerException {
        ((FolderImpl) folder).ensureHasToken();

        String relUrl = HierarchyItemImpl.decodeAndConvertToPath(folder.getPath());
        String destinationFolder = Paths.get(getRootFolder(), relUrl).toString();
        if (isRecursive(relUrl)) {
            throw new ServerException("Cannot copy to subfolder", WebDavStatus.FORBIDDEN);
        }
        if (!Files.exists(Paths.get(destinationFolder)))
            throw new ServerException();
        try {
            Path sourcePath = this.getFullPath();
            Path destinationFullPath = Paths.get(destinationFolder, destName);
            FileUtils.copyDirectory(sourcePath.toFile(), destinationFullPath.toFile());
        } catch (IOException e) {
            throw new ServerException(e);
        }
        setName(destName);
    }
    // copyToFolderImpl >>>>

    /**
     * Check whether current folder is the parent to the destination.
     *
     * @param destFolder Path to the destination folder.
     * @return True if current folder is parent for the destination, false otherwise.
     * @throws ServerException in case of any server exception.
     */
    private boolean isRecursive(String destFolder) throws ServerException {
        return destFolder.startsWith(getPath().replace("/", java.io.File.separator));
    }

    /**
     * Sorts array of FileSystemInfo according to the specified order.
     * @param paths Array of files and folders to sort.
     * @param orderProps Sorting order.
     * @return Sorted list of files and folders.
     */
    private List<Path> sortChildren(List<Path> paths, List<OrderProperty> orderProps) {
        if (orderProps != null && !orderProps.isEmpty()) {
            int index = 0;
            Comparator<Path> comparator = null;
            for (OrderProperty orderProperty :
                    orderProps) {
                Comparator<Path> tempComp = null;
                if ("is-directory".equals(orderProperty.getProperty().getName())) {
                    Function<Path, Boolean> sortFunc = item -> item.toFile().isDirectory();
                    tempComp = Comparator.comparing(sortFunc);
                }
                if ("quota-used-bytes".equals(orderProperty.getProperty().getName())) {
                    Function<Path, Long> sortFunc = item -> item.toFile().length();
                    tempComp = Comparator.comparing(sortFunc);
                }
                if ("getlastmodified".equals(orderProperty.getProperty().getName())) {
                    Function<Path, Long> sortFunc = item -> item.toFile().lastModified();
                    tempComp = Comparator.comparing(sortFunc);
                }
                if ("displayname".equals(orderProperty.getProperty().getName())) {
                    Function<Path, String> sortFunc = item -> item.getFileName().toString();
                    tempComp = Comparator.comparing(sortFunc);
                }
                if ("getcontenttype".equals(orderProperty.getProperty().getName())) {
                    Function<Path, String> sortFunc = item -> getExtension(item.getFileName().toString());
                    tempComp = Comparator.comparing(sortFunc);
                }
                if (tempComp != null) {
                    if (index++ == 0) {
                        if (orderProperty.isAscending()) {
                            comparator = tempComp;
                        } else {
                            comparator = tempComp.reversed();
                        }
                    } else {
                        if (orderProperty.isAscending()) {
                            comparator = comparator != null ? comparator.thenComparing(tempComp) : tempComp;
                        } else {
                            comparator = comparator != null ? comparator.thenComparing(tempComp.reversed()) : tempComp.reversed();
                        }
                    }
                }
            }
            if (comparator != null) {
                paths = paths.stream().sorted(comparator).collect(Collectors.toList());
            }
        }
        return paths;
    }

    private String getExtension(String name) {
        int periodIndex = name.lastIndexOf('.');
        return periodIndex == -1 ? "" : name.substring(periodIndex + 1);

    }

    // <<<< moveToFolderImpl
    @Override
    public void moveTo(Folder folder, String destName) throws LockedException,
            ConflictException, ServerException {
        ensureHasToken();
        ((FolderImpl) folder).ensureHasToken();
        String destinationFolder = Paths.get(getRootFolder(), HierarchyItemImpl.decodeAndConvertToPath(folder.getPath())).toString();
        if (!Files.exists(Paths.get(destinationFolder)))
            throw new ConflictException();
        Path sourcePath = this.getFullPath();
        Path destinationFullPath = Paths.get(destinationFolder, destName);
        try {
            FileUtils.copyDirectory(sourcePath.toFile(), destinationFullPath.toFile());
            delete();
        } catch (IOException e) {
            throw new ServerException(e);
        }
        setName(destName);
    }
    // moveToFolderImpl >>>>

    /**
     * Returns free bytes available to current user.
     *
     * @return Returns free bytes available to current user.
     */
    @Override
    public long getAvailableBytes() {
        return getFullPath().toFile().getFreeSpace();
    }

    /**
     * Returns used bytes by current user.
     *
     * @return Returns used bytes by current user.
     */
    @Override
    public long getUsedBytes() {
        long total = getFullPath().toFile().getTotalSpace();
        return total - getAvailableBytes();
    }
}
