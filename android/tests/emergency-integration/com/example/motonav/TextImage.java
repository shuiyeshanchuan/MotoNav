package com.example.motonav;
import java.util.*;
public final class TextImage {
    public static final TextImage INSTANCE=new TextImage();
    public static final class Img {private final byte[] data;public Img(String text){data=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);}public int getW(){return 1;}public int getH(){return 1;}public byte[] getData(){return data;}}
    // Two pre-rendered fixture slices model the renderer contract; Android Canvas is outside this JVM harness.
    public List<Img> renderEmergency(String text){return text.length()>40?Arrays.asList(new Img(text.substring(0,40)),new Img(text.substring(40))):Collections.singletonList(new Img(text));}
}
