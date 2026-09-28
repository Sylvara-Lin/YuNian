/* page_align_decrypt.ld - Force .lianyu_decrypt to page boundary
 *
 * Problem: .text and .lianyu_decrypt share the same memory page.
 * When mprotect sets .text to PROT_READ|PROT_WRITE (no EXEC for W^X),
 * the .lianyu_decrypt code on the same page becomes non-executable,
 * causing SIGSEGV when the CPU tries to fetch the next instruction.
 *
 * Solution: Page-align .lianyu_decrypt so it's on a separate page
 * from .text. This allows mprotect to change .text permissions
 * without affecting .lianyu_decrypt executability.
 */
SECTIONS
{
    .lianyu_decrypt ALIGN(0x1000) :
    {
        KEEP(*(.lianyu_decrypt))
    }
} INSERT AFTER .text;
