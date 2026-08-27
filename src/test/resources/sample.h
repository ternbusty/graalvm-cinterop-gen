/**
 * Sample C header for testing cinterop-gen.
 */

#ifndef SAMPLE_H
#define SAMPLE_H

#include <stddef.h>
#include <stdint.h>

#define SAMPLE_VERSION 1
#define MAX_NAME_LEN 256
#define PI_APPROX 3.14

enum color {
    COLOR_RED   = 0,
    COLOR_GREEN = 1,
    COLOR_BLUE  = 2,
};

typedef enum {
    LOG_DEBUG = 0,
    LOG_INFO  = 1,
    LOG_WARN  = 2,
    LOG_ERROR = 3,
} log_level_t;

struct point {
    int x;
    int y;
};

struct rect {
    struct point origin;
    int width;
    int height;
};

typedef struct {
    char  name[MAX_NAME_LEN];
    int   id;
    double score;
} record_t;

struct config {
    const char  *key;
    const char  *value;
    struct config *next;
};

union number {
    int    i;
    float  f;
    double d;
};

int   sample_add(int a, int b);
void  sample_greet(const char *name);
void *sample_alloc(size_t size);
void  sample_free(void *ptr);

struct point sample_make_point(int x, int y);
int   sample_distance(const struct point *a, const struct point *b);

int   sample_printf(const char *fmt, ...);

typedef void (*callback_fn)(int code, const char *msg);
void  sample_register_callback(callback_fn cb);

int32_t sample_checksum(const uint8_t *data, size_t len);

#endif /* SAMPLE_H */
