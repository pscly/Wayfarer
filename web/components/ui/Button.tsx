import * as React from "react";

import { cn } from "@/lib/cn";

type Variant = "primary" | "secondary" | "ghost" | "danger";
type Size = "sm" | "md" | "lg";

const base =
  "inline-flex items-center justify-center gap-2 rounded-full font-semibold transition " +
  "disabled:cursor-not-allowed disabled:opacity-60";

const variantClass: Record<Variant, string> = {
  primary: "bg-accent text-accent-foreground hover:brightness-110",
  secondary: "border border-border bg-panel hover:bg-panel-2",
  ghost: "hover:bg-panel",
  danger: "border border-danger/40 bg-danger/10 text-foreground hover:bg-danger/15",
};

const sizeClass: Record<Size, string> = {
  sm: "px-3 py-1.5 text-sm",
  md: "px-4 py-2.5 text-sm",
  lg: "px-5 py-3 text-base",
};

export function Button({
  className,
  variant = "secondary",
  size = "md",
  ...props
}: React.ComponentPropsWithoutRef<"button"> & {
  variant?: Variant;
  size?: Size;
}) {
  return (
    <button
      className={cn(base, variantClass[variant], sizeClass[size], className)}
      {...props}
    />
  );
}
