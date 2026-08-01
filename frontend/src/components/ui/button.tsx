import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { Slot } from "radix-ui"

import { cn } from "@/lib/utils"

/**
 * Material Design 3 button.
 *
 * Two things make this MD3 rather than a rounded rectangle:
 *
 * 1. **Shape.** Every variant is a pill. It is the single most recognisable
 *    trait of the style, so there is deliberately no `rounded` escape hatch —
 *    the FAB is the one exception the spec allows, and it gets its own variant.
 * 2. **State layers.** Interaction never swaps the base colour. Filled surfaces
 *    dial their own colour down (90% hover, 80% pressed); transparent ones pick
 *    the primary up (10% hover). That is why hover reads as *the same button,
 *    touched* instead of a different button.
 *
 * Variant names are kept from the previous neutral system so no call site
 * changes; what each one resolves to is now an MD3 role. `tonal` and `fab` are
 * new and have no legacy equivalent.
 */
const buttonVariants = cva(
  [
    "group/button inline-flex shrink-0 items-center justify-center rounded-full",
    "border border-transparent bg-clip-padding font-medium tracking-[0.01em] whitespace-nowrap select-none",
    // One curve and one duration for every button in the app. MD3's
    // emphasised-decelerate: quick to commit, slow to settle.
    "transition-all duration-200 ease-md",
    // Focus uses outline rather than ring: the offset is transparent, so the
    // indicator stays correct on tonal containers as well as on the surface.
    "outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-md-primary",
    // Tactile press feedback, on every variant.
    "active:scale-95",
    "disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50",
    "aria-invalid:border-md-error aria-invalid:outline-md-error",
    "[&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  ],
  {
    variants: {
      variant: {
        /** Filled. The primary action: seed colour, lifting on hover. */
        default:
          "bg-md-primary text-md-on-primary shadow-[var(--elevation-1)] hover:bg-md-primary/90 hover:shadow-[var(--elevation-2)] active:bg-md-primary/80 aria-expanded:bg-md-primary/90",
        /** Tonal. A full-weight container for secondary actions. */
        tonal:
          "bg-md-secondary-container text-md-on-secondary-container hover:bg-md-secondary-container/70 hover:shadow-[var(--elevation-1)] active:bg-md-secondary-container/60 aria-expanded:bg-md-secondary-container/70",
        /** Outlined. Hairline + state layer, no fill at rest. */
        outline:
          "border-md-outline-variant text-md-on-surface hover:border-md-outline hover:bg-md-primary/8 aria-expanded:border-md-outline aria-expanded:bg-md-primary/8",
        /** Legacy alias for tonal — kept so existing call sites keep working. */
        secondary:
          "bg-md-secondary-container text-md-on-secondary-container hover:bg-md-secondary-container/70 active:bg-md-secondary-container/60 aria-expanded:bg-md-secondary-container/70",
        /** Text. The workhorse for toolbar and sidebar icon actions. */
        ghost:
          "text-md-on-surface-variant hover:bg-md-primary/10 hover:text-md-on-surface aria-expanded:bg-md-primary/10 aria-expanded:text-md-on-surface",
        /** Error container, per MD3 — not a filled red button. */
        destructive:
          "bg-md-error/10 text-md-error hover:bg-md-error/16 active:bg-md-error/20 focus-visible:outline-md-error",
        /** Floating action button: tertiary accent, squircle, real elevation. */
        fab: "rounded-2xl bg-md-tertiary-container text-md-on-tertiary-container shadow-[var(--elevation-2)] hover:shadow-[var(--elevation-3)] active:shadow-[var(--elevation-1)]",
        link: "text-md-primary underline-offset-4 hover:underline",
      },
      size: {
        // Pills need horizontal room to read as pills, so padding is generous
        // relative to height. Leading/trailing padding tightens when an icon
        // sits on that side, per MD3's icon-button metrics.
        default:
          "h-9 gap-2 px-5 text-sm has-data-[icon=inline-end]:pr-4 has-data-[icon=inline-start]:pl-4",
        xs: "h-7 gap-1 px-3 text-xs has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2 [&_svg:not([class*='size-'])]:size-3",
        sm: "h-8 gap-1.5 px-4 text-[0.8rem] has-data-[icon=inline-end]:pr-3 has-data-[icon=inline-start]:pl-3 [&_svg:not([class*='size-'])]:size-3.5",
        lg: "h-11 gap-2 px-6 text-sm has-data-[icon=inline-end]:pr-5 has-data-[icon=inline-start]:pl-5",
        icon: "size-9",
        "icon-xs": "size-7 [&_svg:not([class*='size-'])]:size-3",
        "icon-sm": "size-8 [&_svg:not([class*='size-'])]:size-3.5",
        "icon-lg": "size-11",
        /** 56x56 circular FAB — MD3's canonical size and a generous target. */
        fab: "size-14 [&_svg:not([class*='size-'])]:size-6",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Button({
  className,
  variant = "default",
  size = "default",
  asChild = false,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
  }) {
  const Comp = asChild ? Slot.Root : "button"

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
