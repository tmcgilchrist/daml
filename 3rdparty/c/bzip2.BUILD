package(default_visibility = ["//visibility:public"])

cc_library(
    name = "bz2",
    srcs = [
        "blocksort.c",
        "bzlib.c",
        "compress.c",
        "crctable.c",
        "decompress.c",
        "huffman.c",
        "randtable.c",
    ],
    hdrs = [
        "bzlib.h",
        "bzlib_private.h",
    ],
    includes = [
        ".",
    ],
    visibility = [
        "//visibility:public",
    ],
)

# filegroups for library and headers, to
# be passed to hazel_repositories.

filegroup(
    name = "lib",
    srcs = [":bz2"],
    output_group = "dynamic_library",
)

filegroup(
    name = "headers",
    srcs = [
        "bzlib.h",
    ],
)
