package com.walkgis.tiles.util.storage;

import com.walkgis.tiles.util.render.JpegRenderer;
import com.walkgis.tiles.util.render.PngRenderer;
import com.walkgis.tiles.util.render.Renderer;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;

public class FolderStorage extends Storage {

    private String folder;
    private Renderer renderer;

    private FolderStorage(String folder, Map<String, String> metadata) throws Exception {
        this.folder = folder;
        if (metadata.get("format").equalsIgnoreCase("png"))
            this.renderer = new PngRenderer();
        else if (metadata.get("format").equalsIgnoreCase("jpg"))
            this.renderer = new JpegRenderer(Integer.parseInt(metadata.getOrDefault("compression", "75")));
        else if (metadata.get("format").equalsIgnoreCase(""))
            this.renderer = new PngRenderer();
        else throw new Exception("输出格式错误");
    }

    public static Storage create(String output_folder, Map<String, String> metadata, String s) throws Exception {
        File targetDir = new File(output_folder);
        if (!targetDir.exists()) targetDir.mkdirs();
        return new FolderStorage(output_folder, metadata);
    }

    @Override
    public String filepath(int x, int y, int z, boolean hashed) {
        return folder + File.separator + z + File.separator + String.format("%s_%s.%s", x, y, renderer._suffix);
    }

    @Override
    public void save(int x, int y, int z, BufferedImage image) {

    }
}
