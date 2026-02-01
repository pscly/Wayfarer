import * as React from "react";

import { cn } from "@/lib/cn";

export function FieldLabel({
  className,
  ...props
}: React.ComponentPropsWithoutRef<"span">) {
  return (
    <span
      className={cn(
        "text-xs font-medium tracking-wide text-foreground/70",
        className,
      )}
      {...props}
    />
  );
}

export function FieldHint({
  className,
  ...props
}: React.ComponentPropsWithoutRef<"p">) {
  return (
    <p className={cn("text-xs text-muted", className)} {...props} />
  );
}
