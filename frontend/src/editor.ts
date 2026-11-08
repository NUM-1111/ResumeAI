export interface Resume { initialized?: boolean; text?: string; revision?: number; clientEditSequence?: number }
export interface SaveRequest { text: string; expectedRevision: number; clientEditSequence: number }
export class Editor {
  text = ''; savedText = ''; revision = 0; sequence = 0;
  state: 'CLEAN' | 'DIRTY' | 'SAVING' | 'FAILED' | 'CONFLICT' = 'CLEAN';
  pending: { body: SaveRequest; key: string } | null = null;
  load(resume: Resume) { this.text = this.savedText = resume.text ?? ''; this.revision = resume.revision ?? 0; this.sequence = 0; this.pending = null; this.state = 'CLEAN'; }
  edit(value: string) { this.text = value; this.sequence++; if (this.state !== 'CONFLICT' && this.state !== 'SAVING') this.state = 'DIRTY'; }
  get dirty() { return this.text !== this.savedText || this.pending !== null; }
  async save(send: (body: SaveRequest, key: string) => Promise<Resume>): Promise<boolean> {
    if (this.state === 'SAVING' || this.state === 'CONFLICT') return false;
    if (!this.dirty) { this.state = 'CLEAN'; return true; }
    if (!this.pending) this.pending = { key: crypto.randomUUID(), body: { text: this.text, expectedRevision: this.revision, clientEditSequence: this.sequence } };
    const request = this.pending; this.state = 'SAVING';
    try {
      const response = await send(request.body, request.key);
      if (response.clientEditSequence !== request.body.clientEditSequence || response.text !== request.body.text || !response.revision) throw new Error('保存响应不一致，请保留文本并重试');
      this.revision = response.revision; this.savedText = request.body.text; this.pending = null;
      this.state = this.sequence === request.body.clientEditSequence ? 'CLEAN' : 'DIRTY'; return !this.dirty;
    } catch (error) { this.state = (error as { status?: number }).status === 409 ? 'CONFLICT' : 'FAILED'; throw error; }
  }
}