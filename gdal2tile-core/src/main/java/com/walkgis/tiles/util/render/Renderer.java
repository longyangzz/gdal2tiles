package com.walkgis.tiles.util.render;

import java.awt.image.BufferedImage;
import java.io.IOException;

public abstract class Renderer {
    public String _suffix = "";
    private String tempdir;
    private String suffix;

    public Renderer() {
        if (this.suffix == null)
            this.suffix = this._suffix;
        if (this.tempdir == null)
            this.tempdir = System.getProperty("java.io.tmpdir");
        this.tempdir = tempdir;
    }

    @Override
    public String toString() {
        return String.format("Renderer(suffix={suffix!r})", suffix);
    }

    public abstract byte[] render(BufferedImage image) throws IOException;
}
