/* Page rendering for a view turned by any angle. Plain C with no Android types, so the desktop
 * checks in app/src/test/cpp include it directly. */
#ifndef MONOPAINT_VIEWPORT_TURN_H
#define MONOPAINT_VIEWPORT_TURN_H

#include <stddef.h>
#include <stdint.h>

/* Fills target pixels left..right, top..bottom from the RGBA {@code source}, whose first byte is the
 * tone. {@code m} holds Android Matrix values taking a target position to a source position. Each
 * pixel reads the source pixel under its centre, then shows that tone through the dot patterns by its
 * own screen position, as the quarter-turn paths in viewport_bitmap.c do, so a turn never aliases
 * shades. Pixels off the page are white. */
static void render_turned(const uint8_t *source,uint32_t source_width,uint32_t source_height,size_t source_stride,
        uint8_t *target,size_t target_stride,const float *m,const int32_t *pattern,
        int left,int top,int right,int bottom) {
    for(int y=top;y<bottom;y++) {
        uint32_t *out=(uint32_t *)(target+(size_t)y*target_stride)+left;
        float row_x=m[1]*(y+.5f)+m[2],row_y=m[4]*(y+.5f)+m[5];
        int phase=(y&7)*8;
        for(int x=left;x<right;x++) {
            float sx=m[0]*(x+.5f)+row_x,sy=m[3]*(x+.5f)+row_y;
            if(sx>=0 && sy>=0 && sx<(float)source_width && sy<(float)source_height) {
                int tone=source[(size_t)sy*source_stride+(size_t)sx*4];
                *out++=(uint32_t)pattern[tone*64+phase+(x&7)];
            } else *out++=0xffffffffu;
        }
    }
}

#endif
