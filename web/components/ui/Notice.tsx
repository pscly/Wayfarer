import * as React from "react";

import { cn } from "@/lib/cn";

type Variant = "error" | "info";

const styles: Record<Variant, string> = {
  error: "border-danger/30 bg-danger/10 text-foreground",
  info: "border-border bg-panel text-foreground",
};

export function Notice({
  className,
  variant = "info",
  ...props
}: React.ComponentPropsWithoutRef<"div"> & { variant?: Variant }) {
  return (
    <div
      className={cn(
        "rounded-xl border px-3 py-2 text-sm",
        styles[variant],
        className,
      )}
      {...props}
    />
  );
}
