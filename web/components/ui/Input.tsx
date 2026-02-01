import * as React from "react";

import { cn } from "@/lib/cn";

export function Input({
  className,
  ...props
}: React.ComponentPropsWithoutRef<"input">) {
  return (
    <input
      className={cn(
        "mt-2 w-full rounded-xl border border-border bg-black/20 px-3 py-2 text-sm text-foreground " +
          "placeholder:text-foreground/35 focus:border-border-strong focus:outline-none",
        className,
      )}
      {...props}
    />
  );
}
