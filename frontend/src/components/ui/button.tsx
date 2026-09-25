import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { Slot } from "radix-ui"

import { cn } from "@/lib/utils"

/**
 * The app's button. It keeps Material Design 3's interaction model but not its
 * shape or its scale:
 *
 * 1. **Shape.** A 6px radius (`rounded-md`, the `sm` step of the shape scale)
 *    instead of a pill. Capsules on every control are what made the app read
 *    as a phone UI on a desktop screen. The FAB keeps its squircle.
 * 2. **Scale.** 32px by default and 28px at `sm` — desktop density for a
 *    pointer. The old 36-44px ladder was sized as touch targets.
 * 3. **State layers.** Interaction never swaps the base colour. Filled surfaces
 *    dial their own colour down (90% hover, 80% pressed); transparent ones pick
 *    the primary up (10% hover). That is why hover reads as *the same button,
 *    touched* instead of a different button.
 *
 * Variant names are kept from the previous neutral system so no call site
 * changes; what each one resolves to is an MD3 role. `tonal` and `fab` have no
 * legacy equivalent.
 */
const buttonVariants = cva(
  [
    "group/button inline-flex shrink-0 items-center justify-center rounded-md",
    "border border-transparent bg-clip-padding font-medium tracking-[0.01em] whitespace-nowrap select-none",
    // One curve and one duration for every button in the app. MD3's
    // emphasised-decelerate: quick to commit, slow to settle.
    "transition-all duration-200 ease-md",
    // Focus uses outline rather than ring: the offset is transparent, so the
    // indicator stays correct on tonal containers as well as on the surface.
    "outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-md-primary",
    // Tactile press feedback, on every variant. 2% rather than 5%: at 32px a
    // bigger squeeze reads as the button jumping, not being pressed.
    "active:scale-[0.98]",
    "disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50",
    "aria-invalid:border-md-error aria-invalid:outline-md-error",
    "[&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  ],
  {
    variants: {
      variant: {
        /** Filled. The primary action: seed colour, a light top bevel, and a
         *  ring a shade darker than the fill. The ring stays on hover — only
         *  the fill moves — so the edge never flickers. */
        default:
          "bg-md-primary bg-linear-to-b from-white/12 to-transparent text-md-on-primary shadow-[var(--elevation-button-primary)] hover:bg-md-primary/90 active:bg-md-primary/80 aria-expanded:bg-md-primary/90",
        /** Tonal. A full-weight container for secondary actions. */
        tonal:
          "bg-md-secondary-container text-md-on-secondary-container hover:bg-md-secondary-container/70 hover:shadow-[var(--elevation-1)] active:bg-md-secondary-container/60 aria-expanded:bg-md-secondary-container/70",
        /** Outlined. A white face, a hairline and a hint of shadow, so it
         *  still reads as a button when it sits on a white card. */
        outline:
          "border-md-outline-variant bg-md-surface-container-lowest text-md-on-surface shadow-[var(--elevation-control)] hover:border-md-outline/60 hover:bg-md-surface-container-low aria-expanded:border-md-outline/60 aria-expanded:bg-md-surface-container-low",
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
        // Padding is sized for a squared-off control, not a pill, which needed
        // extra horizontal room to read as one. Leading/trailing padding still
        // tightens when an icon sits on that side.
        default:
          "h-8 gap-1.5 px-3 text-[13px] has-data-[icon=inline-end]:pr-2.5 has-data-[icon=inline-start]:pl-2.5",
        xs: "h-6 gap-1 px-2 text-xs has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3",
        sm: "h-7 gap-1.5 px-2.5 text-[13px] has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2 [&_svg:not([class*='size-'])]:size-3.5",
        lg: "h-9 gap-2 px-4 text-sm has-data-[icon=inline-end]:pr-3 has-data-[icon=inline-start]:pl-3",
        icon: "size-8",
        "icon-xs": "size-6 [&_svg:not([class*='size-'])]:size-3",
        "icon-sm": "size-7 [&_svg:not([class*='size-'])]:size-3.5",
        "icon-lg": "size-9",
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
