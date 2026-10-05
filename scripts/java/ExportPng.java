package io.github.mpdairy.monopaint;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/** Host-side export using the app's document decoder and shared calibration. */
public final class ExportPng {
    public static void main(String[] args) throws Exception {
        String mode=args[3];
        if(!mode.equals("calibrated")&&!mode.equals("raw")&&!mode.equals("dots"))
            throw new IllegalArgumentException("Mode must be calibrated, raw or dots");
        DrawingBook book;
        try(InputStream in=new FileInputStream(args[0])) {book=BookCodec.read(in);}
        book.select(Integer.parseInt(args[1])-1);
        ToneDocument page=book.current();int[] pixels;
        if(mode.equals("dots")){pixels=new int[page.width*page.height];page.render(pixels,0,0,page.width,page.height);}
        else pixels=page.exportPixels(mode.equals("calibrated"));
        BufferedImage image=new BufferedImage(page.width,page.height,BufferedImage.TYPE_INT_RGB);
        image.setRGB(0,0,page.width,page.height,pixels,0,page.width);
        File output=new File(args[2]);
        if(!ImageIO.write(image,"png",output))throw new IOException("PNG writer unavailable");
        BufferedImage check=ImageIO.read(output);
        if(check.getWidth()!=page.width||check.getHeight()!=page.height)throw new IOException("PNG size mismatch");
        int[] actual=check.getRGB(0,0,page.width,page.height,null,0,page.width);
        for(int i=0;i<actual.length;i++)if((actual[i]&0xffffff)!=(pixels[i]&0xffffff))throw new IOException("PNG pixel mismatch");
        System.out.println("Exported page "+(book.index()+1)+": "+page.width+" x "+page.height+", "+mode+" PNG. Verified every pixel.");
    }
}
