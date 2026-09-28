/**
 * string-table.h — Encrypted string table interface.
 *
 * See string-table.cpp for implementation details.
 */
#ifndef STRING_TABLE_H
#define STRING_TABLE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Decrypt a string from the embedded encrypted table.
 * @return 0 on success, -1 on failure. Caller must free via secure_string_free().
 */
int secure_string_get(int id, char** out, size_t* out_len);

/**
 * Zero and free a string from secure_string_get().
 */
void secure_string_free(char* str, size_t len);

#ifdef __cplusplus
}
#endif

#endif /* STRING_TABLE_H */
