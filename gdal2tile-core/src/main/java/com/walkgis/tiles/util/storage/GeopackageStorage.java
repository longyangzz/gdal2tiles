package com.walkgis.tiles.util.storage;

import com.walkgis.tiles.util.geopackage.GeoPackage;
import com.walkgis.tiles.util.render.JpegRenderer;
import com.walkgis.tiles.util.render.PngRenderer;
import com.walkgis.tiles.util.render.Renderer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;

public class GeopackageStorage extends Storage {
    public GeoPackage geopackage;
    private String folder;
    private Renderer renderer;

    private GeopackageStorage(String folder, Map<String, String> metadata) throws Exception {
        this.folder = folder;
        if (metadata.get("format").equalsIgnoreCase("png"))
            this.renderer = new PngRenderer();
        else if (metadata.get("format").equalsIgnoreCase("jpg"))
            this.renderer = new JpegRenderer(Integer.parseInt(metadata.getOrDefault("compression", "75")));
        else throw new Exception("输出格式错误");
    }

    public static Storage create(String folder, Map<String, String> metadata, String version) {
        return null;
    }


    @Override
    public void save(int x, int y, int z, BufferedImage image) throws IOException {
        byte[] contents = this.renderer.render(image);
        this.geopackage.insert(x, y, z, Long.parseLong("2"), contents);
    }

    @Override
    public String filepath(int x, int y, int z, boolean hashed) {
        return null;
    }
}
