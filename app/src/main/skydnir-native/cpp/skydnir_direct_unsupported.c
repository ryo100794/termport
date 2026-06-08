#include <stdio.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "--pdocker-direct-probe") == 0) {
        printf("skydnir-direct-executor:1 process-exec=0 cow-bind=0 unsupported-abi=1\n");
        return 0;
    }
    fprintf(stderr,
            "skydnir-direct: process execution is not implemented for this "
            "Android ABI yet. This binary is packaged to make ABI support "
            "explicit; use arm64-v8a for the current direct runtime.\n");
    return 126;
}
