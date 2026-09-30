cmake_minimum_required(VERSION 3.22.1)
project(monopaint_display C)
add_library(monopaint_display SHARED direct_eink.c viewport_bitmap.c shape_preview.c)
# Keep pen preview and pixel-transfer loops fast in tablet debug builds too.
set_source_files_properties(direct_eink.c viewport_bitmap.c shape_preview.c PROPERTIES COMPILE_OPTIONS "-O2")
# Match Android Matrix's separately rounded multiply/add at pixel boundaries.
# Fused arithmetic can otherwise choose the adjacent source pixel after rotation.
set_source_files_properties(viewport_bitmap.c PROPERTIES COMPILE_OPTIONS "-O2;-ffp-contract=off")
target_compile_options(monopaint_display PRIVATE -Wall -Wextra -Werror)
target_link_libraries(monopaint_display jnigraphics)
