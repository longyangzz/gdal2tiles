//package com.walkgis.tiles.view;
//
//
//import javafx.scene.Group;
//import javafx.scene.Parent;
//import javafx.scene.image.ImageView;
//
//public class DemoSplash extends SplashScreen {
//    @Override
//    public boolean visible() {
//        return super.visible();
//    }
//
//    @Override
//    public String getImagePath() {
//        return "/img/bg.jpg";
//    }
//
//    @Override
//    public Parent getParent() {
//        Group gp = new Group();
//        ImageView imageView = new ImageView(this.getClass().getResource(this.getImagePath()).toExternalForm());
//        gp.getChildren().add(imageView);
//        return gp;
//    }
//}
