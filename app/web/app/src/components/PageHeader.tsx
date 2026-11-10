
export function PageHeader({ title }: { title: string }) {
  return (
    <div className="mx-(--body-gutter) pt-8">
      <h1 className="type-header-emphasized">{title}</h1>
    </div>
  );
}
