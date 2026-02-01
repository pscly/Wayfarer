import Link from "next/link";

import { Button } from "@/components/ui/Button";
import { Card, CardBody } from "@/components/ui/Card";

export default function Home() {
  return (
    <div className="space-y-10">
      <Card>
        <CardBody className="p-8">
          <p className="text-xs tracking-[0.25em] text-foreground/60">WAYFARER</p>
          <h1 className="mt-3 text-balance text-3xl font-semibold text-foreground sm:text-4xl">
            Wayfarer 控制台
          </h1>
          <p className="mt-3 max-w-2xl text-foreground/70">
            中文优先的深色管理台：登录、轨迹与导出。零外链资源，禁止第三方网络请求。
          </p>

          <div className="mt-6 flex flex-wrap gap-3">
            <Link href="/login">
              <Button variant="primary" size="lg">
                登录
              </Button>
            </Link>
            <Link href="/tracks">
              <Button variant="secondary" size="lg">
                进入轨迹
              </Button>
            </Link>
          </div>
        </CardBody>
      </Card>

      <section className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-2xl border border-border bg-panel p-6">
          <h2 className="text-sm font-semibold text-foreground">认证</h2>
          <p className="mt-2 text-sm text-foreground/70">
            使用 Cookie + CSRF 刷新，Web 请求始终携带
            <code className="mx-1 rounded bg-panel px-1 py-0.5 text-xs">
              credentials: include
            </code>
            。
          </p>
        </div>
        <div className="rounded-2xl border border-border bg-panel p-6">
          <h2 className="text-sm font-semibold text-foreground">轨迹</h2>
          <p className="mt-2 text-sm text-foreground/70">
            简洁的数据层：计数、时间区间、列表/表格、导出。
          </p>
        </div>
      </section>
    </div>
  );
}
