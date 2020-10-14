package com.walkgis.tiles.util;

import com.walkgis.tiles.MainApp;
import com.walkgis.tiles.util.sqlite.DatabaseHelper;
import com.walkgis.tiles.util.storage.FolderStorage;
import com.walkgis.tiles.util.storage.GeopackageStorage;
import com.walkgis.tiles.util.storage.MbtilesStorage;
import com.walkgis.tiles.util.storage.Storage;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.beetl.core.Configuration;
import org.beetl.core.GroupTemplate;
import org.beetl.core.Template;
import org.beetl.core.resource.ClasspathResourceLoader;
import org.beetl.core.resource.StringTemplateResourceLoader;
import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconst;
import org.gdal.osr.CoordinateTransformation;
import org.gdal.osr.SpatialReference;
import org.gdal.osr.osr;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by JerFer
 * Date: 2017/12/13.
 */
public class GDAL2Tiles {
    private final static Log logger = LogFactory.getLog(GDAL2Tiles.class);

    private Driver out_drv;
    private Driver mem_drv;
    private Dataset warped_input_dataset;
    private SpatialReference out_srs = null;
    private int nativezoom;
    private List<int[]> tminmax;
    private double[] tsize;
    private GlobalMercator mercator;
    private GlobalGeodetic geodetic;
    private Band alphaband;
    private Integer dataBandsCount;
    private double[] out_gt;
    private double[] tileswne;
    private double[] swne;
    private double ominx, omaxx, omaxy, ominy;

    private String input_file;
    private String output_folder;

    private boolean isepsg4326;
    private String in_srs_wkt;

    // Tile format
    private int tile_size = 256;
    private String tiledriver = "PNG";
    private String tileext = "png";
    private String tmp_dir = "";
    private String tmp_vrt_filename = "";

    private boolean scaledquery = true;

    private int querysize = 4 * this.tile_size;
    private boolean overviewquery = false;
    private int tminz, tmaxz;

    private boolean kml = false;

    private OptionObj options = null;

    private Storage storage;

    public GDAL2Tiles() {
        this.tmp_dir = System.getProperty("java.io.tmpdir");
        this.tmp_vrt_filename = new File(this.tmp_dir, UUID.randomUUID().toString() + ".vrt").getAbsolutePath();
        new File(this.tmp_vrt_filename).deleteOnExit();
        this.scaledquery = true;
        this.querysize = 4 * this.tile_size;
        this.overviewquery = false;
    }

    public GDAL2Tiles(String input_file, String output_folder, OptionObj options) {
        this.tmp_dir = System.getProperty("java.io.tmpdir");
        this.tmp_vrt_filename = new File(this.tmp_dir, UUID.randomUUID().toString() + ".vrt").getAbsolutePath();
        new File(this.tmp_vrt_filename).deleteOnExit();
        this.scaledquery = true;
        this.querysize = 4 * this.tile_size;
        this.overviewquery = false;

        this.input_file = input_file;
        this.output_folder = output_folder;
        this.options = options;

        if (options.resampling.equalsIgnoreCase("near"))
            this.querysize = this.tile_size;
        else if (options.resampling.equalsIgnoreCase("bilinear"))
            this.querysize = this.tile_size * 2;

        this.tminz = 0;
        this.tmaxz = 0;
        if (!(options.zoom == null || "".equals(options.zoom))) {
            String zoom_min = this.options.zoom.split("-")[0];
            String zoom_max = this.options.zoom.split("-").length > 1 ? this.options.zoom.split("-")[1] : "";
            if (!(zoom_max == null || "".equals(zoom_max)))
                this.tmaxz = Integer.parseInt(zoom_max);
            else this.tmaxz = Integer.parseInt(zoom_min);
        }
        this.kml = this.options.kml;
    }

    public void setInput_file(String input_file) {
        this.input_file = input_file;
    }

    public void setOutput_folder(String output_folder) {
        this.output_folder = output_folder;
    }

    public void setOptions(OptionObj options) {
        this.options = options;
        if (options.resampling.equalsIgnoreCase("near"))
            this.querysize = this.tile_size;
        else if (options.resampling.equalsIgnoreCase("bilinear"))
            this.querysize = this.tile_size * 2;

        this.tminz = 0;
        this.tmaxz = 0;
        if (!(options.zoom == null || "".equals(options.zoom))) {
            String zoom_min = this.options.zoom.split("-")[0];
            String zoom_max = this.options.zoom.split("-").length > 1 ? this.options.zoom.split("-")[1] : "";
            if (!(zoom_max == null || "".equals(zoom_max)))
                this.tmaxz = Integer.parseInt(zoom_max);
            else this.tmaxz = Integer.parseInt(zoom_min);
        }
        this.kml = this.options.kml;
    }

    public OptionObj getOptions() {
        return options;
    }

    public String getOutput_folder() {
        return output_folder;
    }

    public void open_input() throws Exception {
        this.out_drv = gdal.GetDriverByName(this.tiledriver);
        this.mem_drv = gdal.GetDriverByName("MEM");

        if (this.out_drv == null)
            throw new Exception(String.format("The '%s' driver was not found, is it available in this GDAL build?", this.tiledriver));

        if (this.mem_drv == null)
            throw new Exception("The 'MEM' driver was not found, is it available in this GDAL build?");

        Dataset input_dataset = null;
        if (new File(this.input_file).exists())
            input_dataset = gdal.Open(this.input_file, gdalconst.GA_ReadOnly);
        else
            throw new Exception("No input file was specified");

        if (input_dataset == null)
            throw new Exception(String.format("It is not possible to open the input file '%s'.", this.input_file));

        if (input_dataset.GetRasterCount() == 0)
            throw new Exception(String.format("Input file '%s' has no raster band", this.input_file));

        if (input_dataset.GetRasterBand(1).GetColorTable() != null)
            throw new Exception(String.format("Please convert this file to RGB/RGBA and run gdal2tiles on the result.",
                    "From paletted file you can create RGBA file (temp.vrt) by:\n" +
                            "gdal_translate -of vrt -expand rgba %s temp.vrt\n" +
                            "then run:\n" +
                            "gdal2tiles temp.vrt", this.input_file));

        Double[] in_nodata = CommonUtils.setup_no_data_values(input_dataset, this.options);

        SpatialReference in_srs = new SpatialReference();
        this.in_srs_wkt = CommonUtils.setup_input_srs(input_dataset, this.options, in_srs);

        this.warped_input_dataset = null;


        this.out_srs = CommonUtils.setup_output_srs(in_srs, options);

        if (this.options.profile.equalsIgnoreCase("mercator") ||
                this.options.profile.equalsIgnoreCase("geodetic")) {
            if (in_srs == null)
                throw new Exception("Input file has unknown SRS." +
                        "Use --s_srs ESPG:xyz (or similar) to provide source reference system.");

            if (!CommonUtils.has_georeference(input_dataset))
                throw new Exception("There is no georeference - neither affine transformation (worldfile) " +
                        "nor GCPs. You can generate only 'raster' profile tiles." +
                        "Either gdal2tiles with parameter -p 'raster' or use another GIS " +
                        "software for georeference e.g. gdal_transform -gcp / -a_ullr / -a_srs");

            if (!(in_srs.ExportToProj4().equalsIgnoreCase(this.out_srs.ExportToProj4())) ||
                    (input_dataset.GetGCPCount() != 0)
            ) {
                this.warped_input_dataset = CommonUtils.reproject_dataset(input_dataset, in_srs, this.out_srs);
                if (in_nodata != null)
                    this.warped_input_dataset = CommonUtils.update_no_data_values(this.warped_input_dataset, in_nodata, this.options);
                else
                    this.warped_input_dataset = CommonUtils.update_alpha_value_for_non_alpha_inputs(this.warped_input_dataset, this.options);
            }
        }

        if (this.warped_input_dataset == null)
            this.warped_input_dataset = input_dataset;

        gdal.GetDriverByName("VRT").CreateCopy(this.tmp_vrt_filename, this.warped_input_dataset);

        this.alphaband = this.warped_input_dataset.GetRasterBand(1).GetMaskBand();
        this.dataBandsCount = CommonUtils.nb_data_bands(this.warped_input_dataset);

        this.isepsg4326 = false;
        SpatialReference srs4326 = new SpatialReference();
        srs4326.ImportFromEPSG(4326);
        if (this.out_srs != null && srs4326.ExportToProj4().equalsIgnoreCase(this.out_srs.ExportToProj4())) {
            this.kml = true;
            this.isepsg4326 = true;
        }

        this.out_gt = this.warped_input_dataset.GetGeoTransform();

        if (!(this.out_gt[2] == 0 && this.out_gt[4] == 0))
            throw new Exception("Georeference of the raster contains rotation or skew. " +
                    "Such raster is not supported. Please use gdalwarp first.");


        this.ominx = out_gt[0];
        this.omaxx = out_gt[0] + this.warped_input_dataset.getRasterXSize() * this.out_gt[1];
        this.omaxy = out_gt[3];
        this.ominy = out_gt[3] - this.warped_input_dataset.getRasterYSize() * this.out_gt[1];

        if (this.options.profile.equalsIgnoreCase("mercator")) {

            this.mercator = new GlobalMercator(256);
//            this.tileswne = this.mercator.tileLatLonBounds();
            this.tminmax = new LinkedList<>();

            for (int tz = 0; tz < 32; tz++) {
                int[] tminxy = this.mercator.metersToTile(this.ominx, this.ominy, tz);
                int[] tmaxxy = this.mercator.metersToTile(this.omaxx, this.omaxy, tz);

                tminxy = new int[]{Math.max(0, tminxy[0]), Math.max(0, tminxy[1])};
                tmaxxy = new int[]{(int) Math.min(Math.pow(2, tz) - 1, tmaxxy[0]), (int) Math.min(Math.pow(2, tz) - 1, tmaxxy[1])};

                this.tminmax.add(tz, new int[]{tminxy[0], tminxy[1], tmaxxy[0], tmaxxy[1]});
            }

            if (this.tminz == 0) {
                this.tminz = this.mercator.zoomForPixelSize(this.out_gt[1] * Math.max(this.warped_input_dataset.getRasterXSize(), this.warped_input_dataset.getRasterYSize()) / (float) (this.tile_size));
            }
            if (this.tmaxz == 0) {
                this.tmaxz = this.mercator.zoomForPixelSize(this.out_gt[1]);
            }
        }
        if (this.options.profile.equalsIgnoreCase("geodetic")) {
            this.geodetic = new GlobalGeodetic(null, 256);
//            this.tileswne = this.geodetic.tileLatLonBounds();
            this.tminmax = new LinkedList<>();
            for (int tz = 0; tz < 32; tz++) {
                int[] tminxy = this.geodetic.lonlatToTile(this.ominx, this.ominy, tz);
                int[] tmaxxy = this.geodetic.lonlatToTile(this.omaxx, this.omaxy, tz);

                tminxy = new int[]{Math.max(0, tminxy[0]), Math.max(0, tminxy[1])};
                tmaxxy = new int[]{Math.min((int) Math.pow(2, tz + 1) - 1, tmaxxy[0]),
                        (int) Math.min(Math.pow(2, tz) - 1, tmaxxy[1])};

                this.tminmax.add(tz, new int[]{tminxy[0], tminxy[1], tmaxxy[0], tmaxxy[1]});
            }

            if (this.tminz == 0) {
                this.tminz = this.geodetic.zoomForPixelSize(this.out_gt[1] *
                        Math.max(this.warped_input_dataset.getRasterXSize(), this.warped_input_dataset.getRasterYSize()) / (float) (this.tile_size));
            }
            if (this.tmaxz == 0) {
                this.tmaxz = this.geodetic.zoomForPixelSize(this.out_gt[1]);
            }
        }
        if (this.options.profile.equalsIgnoreCase("raster")) {
            this.nativezoom = (int) (Math.max(Math.ceil(log2(this.warped_input_dataset.getRasterXSize() / (float) (this.tile_size))),
                    Math.ceil(log2(this.warped_input_dataset.getRasterYSize() / (float) (this.tile_size)))));

            if (this.tminz == 0) {
                this.tminz = 0;
            }
            if (this.tmaxz == 0) {
                this.tmaxz = this.nativezoom;
            }
            this.tminmax = new LinkedList<>();
            this.tsize = new double[this.tmaxz + 1];

            for (int tz = 0; tz < this.tmaxz + 1; tz++) {
                double tsize = Math.pow(2.0, this.nativezoom - tz) * this.tile_size;

                int[] tminxy = new int[]{0, 0};
                int[] tmaxxy = new int[]{
                        ((int) (Math.ceil(this.warped_input_dataset.getRasterXSize() / tsize))) - 1,
                        ((int) (Math.ceil(this.warped_input_dataset.getRasterYSize() / tsize))) - 1
                };

                this.tsize[tz] = Math.ceil(tsize);
                this.tminmax.add(tz, new int[]{tminxy[0], tminxy[1], tmaxxy[0], tmaxxy[1]});
            }

            if (this.kml && !(this.in_srs_wkt == null || "".equals(this.in_srs_wkt))) {
                CoordinateTransformation ct = osr.CreateCoordinateTransformation(in_srs, srs4326);

            }
//            this.tileswne = new int[]{0, 0, 0, 0};
        }
    }

    public int getTminz() {
        return tminz;
    }

    public int getTmaxz() {
        return tmaxz;
    }

    public void generate_metadata() {
        if (Files.isDirectory(Path.of(this.output_folder))) {
            if (!new File(this.output_folder).exists())
                new File(this.output_folder).mkdirs();
        }

        double[] southWest = new double[2];
        double[] northEast = new double[2];
        if (this.options.profile.equalsIgnoreCase("mercator")) {
            southWest = this.mercator.metersToLatLon(this.ominx, this.ominy);
            northEast = this.mercator.metersToLatLon(this.omaxx, this.omaxy);

            southWest = new double[]{Math.max(-85.05112878, southWest[0]), Math.max(-180.0, southWest[1])};
            northEast = new double[]{Math.min(85.05112878, northEast[0]), Math.min(180.0, northEast[1])};

            this.swne = new double[]{southWest[0], southWest[1], northEast[0], northEast[1]};

            if ((this.options.webviewer.equalsIgnoreCase("all") ||
                    this.options.webviewer.equalsIgnoreCase("google")) && this.options.profile.equalsIgnoreCase("mercator"))
                this.generate_googlemaps();

            if ((this.options.webviewer.equalsIgnoreCase("all") ||
                    this.options.webviewer.equalsIgnoreCase("openlayers"))
            )
                this.generate_openlayers();
            if ((this.options.webviewer.equalsIgnoreCase("all") ||
                    this.options.webviewer.equalsIgnoreCase("leaflet"))
            )
                this.generate_leaflet();

        }
        if (this.options.profile.equalsIgnoreCase("geodetic")) {
            southWest = new double[]{this.ominy, this.ominx};
            northEast = new double[]{this.omaxy, this.omaxx};

            southWest = new double[]{Math.max(-90.0, southWest[0]), Math.max(-180.0, southWest[1])};
            northEast = new double[]{Math.min(90.0, northEast[0]), Math.min(180.0, northEast[1])};

            this.swne = new double[]{southWest[0], southWest[1], northEast[0], northEast[1]};

            if ((this.options.webviewer.equalsIgnoreCase("all") ||
                    this.options.webviewer.equalsIgnoreCase("openlayers"))
            )
                this.generate_openlayers();

        }
        if (this.options.profile.equalsIgnoreCase("raster")) {

            southWest = new double[]{this.ominy, this.ominx};
            northEast = new double[]{this.omaxy, this.omaxx};

            this.swne = new double[]{southWest[0], southWest[1], northEast[0], northEast[1]};
            if ((this.options.webviewer.equalsIgnoreCase("all") ||
                    this.options.webviewer.equalsIgnoreCase("openlayers"))
            )
                this.generate_openlayers();
        }


        Map<String, String> metadata = new HashMap<String, String>();
        metadata.put("description", "");
        metadata.put("format", "png");
        metadata.put("name", new File(this.input_file).getName());
        metadata.put("type", "OVERLAY");
        metadata.put("version", "1.0.0");

        var list = Arrays.stream(this.swne).mapToObj(x -> String.valueOf(x)).collect(Collectors.toList());
        metadata.put("bounds", String.join(",", list));

        try {
            if (new File(this.output_folder).isDirectory()) {
                storage = FolderStorage.create(this.output_folder, metadata, "1.0.0");
            } else if (this.output_folder.endsWith(".mbtiles")) {
                storage = MbtilesStorage.create(this.output_folder, metadata, "1.0.0");
            } else if (this.output_folder.endsWith(".gpkg")) {
                storage = GeopackageStorage.create(this.output_folder, metadata, "1.0.0");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        // Generate tilemapresource.xml.
        this.generate_tilemapresource();
        if (this.kml) {
            List<int[]> children = new ArrayList<>();

            int[] xyminmax = this.tminmax.get(this.tminz);
            for (int x = xyminmax[0]; x < xyminmax[2] + 1; x++)
                for (int y = xyminmax[1]; y < xyminmax[3] + 1; y++)
                    children.add(new int[]{x, y, this.tminz});
            if (this.kml) {
                CommonUtils.generate_kml(0, 0, 0, this.tileext, this.tile_size, this.tileswne, this.options, children);
            }
        }
    }

    /**
     * Generation of the base tiles (the lowest in the pyramid) directly from the input raster
     */
    public TileJobInfo generate_base_tiles(List<TileDetail> tileDetails) throws SQLException {
        logger.info("Generating Base Tiles:");

        logger.info("Tiles generated from the max zoom level:");

        int tminx = this.tminmax.get(this.tmaxz)[0];
        int tminy = this.tminmax.get(this.tmaxz)[1];
        int tmaxx = this.tminmax.get(this.tmaxz)[2];
        int tmaxy = this.tminmax.get(this.tmaxz)[3];

        Dataset ds = this.warped_input_dataset;

        int tilebands = this.dataBandsCount + 1;
        int querysize = this.querysize;

        int tcount = (1 + Math.abs(tmaxx - tminx)) * (1 + Math.abs(tmaxy - tminy));

        long ti = 0;

        int tz = this.tmaxz;

        for (int ty = tmaxy; ty > tminy - 1; ty--) {
            for (int tx = tminx; tx < tmaxx + 1; tx++) {

                ti += 1;
                double[] b = null;
                if (this.options.profile.equalsIgnoreCase("mercator")) {
                    b = this.mercator.tileBounds(tx, ty, tz);
                } else if (this.options.profile.equalsIgnoreCase("geodetic")) {
                    b = this.geodetic.tileBounds(tx, ty, tz);
                }

                int rx = 0, ry = 0, rxsize = 0, rysize = 0, wx = 0, wy = 0, wxsize = 0, wysize = 0;

                if (this.options.profile.equalsIgnoreCase("mercator") ||
                        this.options.profile.equalsIgnoreCase("geodetic")) {
                    int[][] rbwb = this.geo_query(ds, b[0], b[3], b[2], b[1], 0);

//                    nativesize = rbwb[1][0] + rbwb[1][2];

                    rbwb = this.geo_query(ds, b[0], b[3], b[2], b[1], querysize);

                    rx = rbwb[0][0];
                    ry = rbwb[0][1];
                    rxsize = rbwb[0][2];
                    rysize = rbwb[0][3];
                    wx = rbwb[1][0];
                    wy = rbwb[1][1];
                    wxsize = rbwb[1][2];
                    wysize = rbwb[1][3];
                } else {
                    int tsize = (int) this.tsize[tz];//tilesize in raster coordinates for actual zoom
                    int xsize = this.warped_input_dataset.getRasterXSize();//size of the raster in pixels
                    int ysize = this.warped_input_dataset.getRasterYSize();
                    if (tz >= this.nativezoom) {
                        querysize = this.tile_size;//int(2 * * (self.nativezoom - tz) * self.tilesize)
                    }

                    rx = (tx) * tsize;
                    rxsize = 0;
                    if (tx == tmaxx)
                        rxsize = xsize % tsize;
                    if (rxsize == 0)
                        rxsize = tsize;

                    rysize = 0;
                    if (ty == tmaxy)
                        rysize = ysize % tsize;
                    if (rysize == 0)
                        rysize = tsize;
                    ry = ysize - (ty * tsize) - rysize;

                    wx = 0;
                    wy = 0;
                    wxsize = (int) (rxsize / (float) (tsize) * this.tile_size);
                    wysize = (int) (rysize / (float) (tsize) * this.tile_size);

                    if (wysize != this.tile_size)
                        wy = this.tile_size - wysize;
                }
                ///开始处理图片了//////////////////////////////////////////////////////////////////////

                tileDetails.add(new TileDetail(ti, tx, ty, tz, rx, ry, rxsize, rysize, wx, wy, wxsize, wysize, querysize));
            }
        }

        TileSwne tileSwne = createDealFun();
        return new TileJobInfo(
                tileSwne,
                this.storage,
                this.tmp_vrt_filename,
                this.dataBandsCount,
                this.output_folder,
                this.tileext,
                this.tiledriver,
                this.tile_size,
                this.kml,
                this.tminmax,
                this.tminz,
                this.tmaxz,
                this.in_srs_wkt,
                this.out_gt,
                this.ominy,
                this.isepsg4326,
                this.options,
                this.options.exclude_transparent
        );
    }

    private TileSwne createDealFun() {
        return (storage, out_drv, dstile, tx, ty, tz, tileext) -> {
            if (Files.isDirectory(Path.of(this.output_folder))) {
                String tilefilename = storage.filepath(tx, ty, tz, false);
                out_drv.CreateCopy(tilefilename, dstile, 0);
            } else {
                String suffix = this.output_folder.substring(this.output_folder.lastIndexOf(".")).toLowerCase();
                if (suffix.equalsIgnoreCase(".gpkg")) {
                    File file = new File(this.tmp_dir, UUID.randomUUID().toString() + ".png");
                    out_drv.CreateCopy(file.getAbsolutePath(), dstile, 0);
                    storage.save(tx, ty, tz, ImageIO.read(file));
                    file.delete();
                } else if (suffix.equalsIgnoreCase(".mbtiles")) {

                }
            }
        };
    }

    private int[][] geo_query(Dataset ds, double ulx, double uly, double lrx, double lry, int querysize) {
        double[] geotran = ds.GetGeoTransform();
        int rx = (int) ((ulx - geotran[0]) / geotran[1] + 0.001);
        int ry = (int) ((uly - geotran[3]) / geotran[5] + 0.001);
        int rxsize = (int) ((lrx - ulx) / geotran[1] + 0.5);
        int rysize = (int) ((lry - uly) / geotran[5] + 0.5);

        int wxsize, wysize;
        if (querysize == 0) {
            wxsize = rxsize;
            wysize = rysize;
        } else {
            wxsize = querysize;
            wysize = querysize;
        }

        int wx = 0;
        if (rx < 0) {
            int rxshift = Math.abs(rx);
            wx = (int) (wxsize * ((float) (rxshift) / rxsize));
            wxsize = wxsize - wx;
            rxsize = rxsize - (int) (rxsize * ((float) (rxshift) / rxsize));
            rx = 0;
        }
        if ((rx + rxsize) > ds.getRasterXSize()) {
            wxsize = (int) (wxsize * ((float) (ds.getRasterXSize() - rx) / rxsize));
            rxsize = ds.getRasterXSize() - rx;
        }

        int wy = 0;

        if (ry < 0) {
            int ryshift = Math.abs(ry);
            wy = (int) (wysize * ((float) (ryshift) / rysize));
            wysize = wysize - wy;
            rysize = rysize - (int) (rysize * ((float) (ryshift) / rysize));
            ry = 0;
        }

        if ((ry + rysize) > ds.getRasterYSize()) {
            wysize = (int) (wysize * ((float) (ds.getRasterYSize() - ry) / rysize));
            rysize = ds.getRasterYSize() - ry;
        }
        return new int[][]{new int[]{rx, ry, rxsize, rysize}, new int[]{wx, wy, wxsize, wysize}};
    }

    private void generate_tilemapresource() {
        Map<String, Object> args = new HashMap<>();
        args.put("title", this.options.title);
        args.put("south", this.swne[0]);
        args.put("west", this.swne[1]);
        args.put("north", this.swne[2]);
        args.put("east", this.swne[3]);

        args.put("tile_size", this.tile_size);
        args.put("tileformat", this.tileext);
        args.put("publishurl", "this.options.url");
        args.put("profile", this.options.profile);

        if (this.options.profile.equalsIgnoreCase("mercator"))
            args.put("srs", "EPSG:3857");
        else if (this.options.profile.equalsIgnoreCase("geodetic"))
            args.put("srs", "EPSG:4326");
        else if (!(this.options.s_srs == null || "".equals(this.options.s_srs)))
            args.put("srs", this.options.s_srs);
        else if (this.out_srs != null)
            args.put("srs", this.out_srs.ExportToWkt());
        else args.put("srs", "");

        String s = "";
        try {
            //初始化代码
            ClasspathResourceLoader resourceLoader = new ClasspathResourceLoader("/");
            //获取模板
            GroupTemplate gt = new GroupTemplate(resourceLoader, Configuration.defaultConfiguration());
            Template template = gt.getTemplate("tilemapresource.xml");
            //渲染结果
            template.binding(args);
            String str = template.render();

            //保存文本到文件
            Path rootLocation = null;
            if (Files.isDirectory(Path.of(this.output_folder)))
                rootLocation = Paths.get(this.output_folder);
            else rootLocation = Paths.get(new File(this.output_folder).getParent());

            if (Files.notExists(rootLocation)) Files.createDirectories(rootLocation);
            //data.js是文件
            Path path = rootLocation.resolve("tilemapresource.html");
            byte[] strToBytes = str.getBytes();
            Files.write(path, strToBytes);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void generate_googlemaps() {

    }

    private void generate_leaflet() {
        Map<String, Object> args = new HashMap<>();
        args.put("title", "");
        args.put("htmltitle", "");
        args.put("bingkey", "this.options.bingkey");
        args.put("south", this.swne[0]);
        args.put("west", this.swne[1]);
        args.put("north", this.swne[2]);
        args.put("east", this.swne[3]);

        args.put("centerlon", (this.swne[1] + this.swne[3]) / 2);
        args.put("centerlat", (this.swne[2] + this.swne[0]) / 2);

        args.put("minzoom", this.tminz);
        args.put("maxzoom", this.tmaxz);
        args.put("beginzoom", this.tmaxz);

        args.put("tile_size", this.tile_size);
        args.put("tileformat", this.tileext);
        args.put("publishurl", "this.options.url");
        args.put("copyright", "this.options.copyright");
        args.put("profile", this.options.profile);

        String s = "";
        try {
            InputStream inputStream = MainApp.class.getClassLoader().getResourceAsStream("view/leaflet.html");

            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
            StringBuffer content = new StringBuffer();
            while ((s = br.readLine()) != null) {
                content = content.append(s);
            }

            //初始化代码
            StringTemplateResourceLoader resourceLoader = new StringTemplateResourceLoader();
            Configuration cfg = Configuration.defaultConfiguration();
            GroupTemplate gt = new GroupTemplate(resourceLoader, cfg);
            //获取模板
            Template t = gt.getTemplate(content.toString());
            t.binding(args);
            //渲染结果
            String str = t.render();

            //保存文本到文件
            Path rootLocation = null;
            if (Files.isDirectory(Path.of(this.output_folder)))
                rootLocation = Paths.get(this.output_folder);
            else rootLocation = Paths.get(new File(this.output_folder).getParent());

            if (Files.notExists(rootLocation)) Files.createDirectories(rootLocation);
            //data.js是文件
            Path path = rootLocation.resolve("leaflet.html");
            byte[] strToBytes = str.getBytes();
            Files.write(path, strToBytes);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void generate_openlayers() {
        Map<String, Object> args = new HashMap<>();
        args.put("title", "openlayers3");
        args.put("bingkey", "this.options.bingkey");
        args.put("south", this.swne[0]);
        args.put("west", this.swne[1]);
        args.put("north", this.swne[2]);
        args.put("east", this.swne[3]);

        args.put("centerlon", (this.swne[1] + this.swne[3]) / 2);
        args.put("centerlat", (this.swne[2] + this.swne[0]) / 2);

        args.put("minzoom", this.tminz);
        args.put("maxzoom", this.tmaxz);
        args.put("tile_size", this.tile_size);
        args.put("tileformat", this.tileext);
        args.put("publishurl", "this.options.url");
        args.put("copyright", "this.options.copyright");

        args.put("tmsoffset", "-1");
        if (this.options.profile.equalsIgnoreCase("raster")) {
            args.put("rasterzoomlevels", this.tmaxz + 1);
            args.put("rastermaxresolution", Math.pow(2, this.nativezoom) * this.out_gt[1]);
        }
        args.put("profile", this.options.profile);

        try {
            //初始化代码
            ClasspathResourceLoader resourceLoader = new ClasspathResourceLoader("/");
            //获取模板
            GroupTemplate gt = new GroupTemplate(resourceLoader, Configuration.defaultConfiguration());
            Template template = gt.getTemplate("view/openlayers3.html");
            //渲染结果
            template.binding(args);
            String str = template.render();

            //保存文本到文件
            Path rootLocation = null;
            if (Files.isDirectory(Path.of(this.output_folder)))
                rootLocation = Paths.get(this.output_folder);
            else rootLocation = Paths.get(new File(this.output_folder).getParent());

            if (Files.notExists(rootLocation)) Files.createDirectories(rootLocation);
            //data.js是文件
            Path path = rootLocation.resolve("openlayers3.html");
            byte[] strToBytes = str.getBytes();
            Files.write(path, strToBytes);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private double log2(double x) {
        return Math.log10(x) / Math.log10(2);
    }
}
