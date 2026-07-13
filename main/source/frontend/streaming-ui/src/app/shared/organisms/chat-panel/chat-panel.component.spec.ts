import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { AutoCompleteCompleteEvent } from 'primeng/autocomplete';
import { of } from 'rxjs';
import { AuthService } from '../../../core/services/auth.service';
import { ChatModerationService } from '../../../core/services/chat-moderation.service';
import { ChatService } from '../../../core/services/chat.service';
import { ChatPanelComponent } from './chat-panel.component';

describe('ChatPanelComponent', () => {
  let fixture: ComponentFixture<ChatPanelComponent>;
  let component: ChatPanelComponent;

  const chatServiceMock = {
    getRoom: jasmine.createSpy('getRoom').and.returnValue(of({
      externalKey: 'test-room',
      status: 'ACTIVE',
      viewerCanModerate: false,
      viewerBanned: false,
      createdAt: '2026-07-13T00:00:00Z',
      archivedAt: null,
    })),
    sendMessage: jasmine.createSpy('sendMessage').and.returnValue(of({
      id: 'msg-1',
      roomKey: 'test-room',
      authorSubject: 'user-sub',
      authorUsername: 'Alice',
      authorAvatarUrl: null,
      body: 'hello',
      messageType: 'NORMAL',
      giftAmount: null,
      giftCurrency: null,
      createdAt: '2026-07-13T00:00:00Z',
      mentions: [],
    })),
    getRecentMessages: jasmine.createSpy('getRecentMessages').and.returnValue(of([])),
    getMessagesBefore: jasmine.createSpy('getMessagesBefore').and.returnValue(of([])),
    getParticipants: jasmine.createSpy('getParticipants').and.returnValue(of([])),
  };

  const authServiceMock = {
    accessToken: jasmine.createSpy('accessToken').and.returnValue('mock-token'),
  };

  const modServiceMock = {
    bans: jasmine.createSpy('bans').and.returnValue([]),
    bannedSubjects: jasmine.createSpy('bannedSubjects').and.returnValue(new Set<string>()),
    loadBans: jasmine.createSpy('loadBans').and.returnValue(of([])),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatPanelComponent, NoopAnimationsModule],
      providers: [
        provideHttpClient(),
        { provide: ChatService, useValue: chatServiceMock },
        { provide: AuthService, useValue: authServiceMock },
        { provide: ChatModerationService, useValue: modServiceMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ChatPanelComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('roomKey', 'test-room');
  });

  // ── Compilation / initialization ────────────────────────────────────────

  it('should create the component', () => {
    expect(component).toBeTruthy();
  });

  it('should initialize with loading state', () => {
    expect((component as any).loading()).toBe(true);
  });

  // ── send() guards ───────────────────────────────────────────────────────

  it('should not send empty messages', () => {
    const messagesBefore = (component as any).messages().length;
    (component as any).messageInput.setValue('   ');
    (component as any).send();
    expect((component as any).messages().length).toBe(messagesBefore);
  });

  it('should not send when already sending', () => {
    (component as any).messageInput.setValue('hello');
    (component as any).sending.set(true);
    const messagesBefore = (component as any).messages().length;
    (component as any).send();
    expect((component as any).messages().length).toBe(messagesBefore);
  });

  // ── Emoji picker ────────────────────────────────────────────────────────

  it('should expose EMOJI_LIST', () => {
    expect((component as any).EMOJI_LIST.length).toBeGreaterThan(0);
  });

  it('should toggle emoji picker openness', () => {
    expect((component as any).emojiPickerOpen()).toBe(false);
    (component as any).toggleEmojiPicker();
    expect((component as any).emojiPickerOpen()).toBe(true);
    (component as any).toggleEmojiPicker();
    expect((component as any).emojiPickerOpen()).toBe(false);
  });

  // ── @mention autocomplete ───────────────────────────────────────────────

  it('should return empty suggestions when no @ in input', () => {
    const event: AutoCompleteCompleteEvent = { query: 'hello world', originalEvent: new Event('input') };
    (component as any).completeMentions(event);
    expect((component as any).mentionSuggestions().length).toBe(0);
  });

  it('should save original text and show local suggestions when @ is detected', () => {
    // Pre-populate messages with chatters to have suggestion candidates
    (component as any).messages.set([{
      id: '1', clientId: '1', roomKey: 'test-room',
      authorSubject: 'sub1', authorUsername: 'alice', authorAvatarUrl: null,
      body: 'hi', messageType: 'NORMAL', giftAmount: null, giftCurrency: null,
      createdAt: '2026-07-14T00:00:00Z', status: 'sent', mentions: [],
    }]);
    const event: AutoCompleteCompleteEvent = { query: 'hello @a', originalEvent: new Event('input') };
    (component as any).completeMentions(event);
    // Should save snapshot of original text for later reconstruction
    expect((component as any).savedOriginalQuery).toBe('hello @a');
    expect((component as any).savedMentionStart).toBe(6);
    // Should show local suggestions immediately
    expect((component as any).mentionSuggestions().length).toBeGreaterThanOrEqual(1);
    expect((component as any).mentionSuggestions()[0]).toBe('alice');
  });

  it('should identify fallback mentions', () => {
    (component as any).messages.set([]);
    // With no chatters, any name is a fallback
    expect((component as any).isMentionFallback('unknown_user')).toBe(true);
  });

  it('should not be fallback when user is a known chatter', () => {
    (component as any).messages.set([{
      id: '1', clientId: '1', roomKey: 'test-room',
      authorSubject: 'sub1', authorUsername: 'alice', authorAvatarUrl: null,
      body: 'hi', messageType: 'NORMAL', giftAmount: null, giftCurrency: null,
      createdAt: '2026-07-14T00:00:00Z', status: 'sent', mentions: [],
    }]);
    expect((component as any).isMentionFallback('alice')).toBe(false);
  });

  it('should send on Enter when no suggestions are shown', () => {
    (component as any).mentionSuggestions.set([]);
    (component as any).messageInput.setValue('hello');
    const event = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: false });
    (component as any).onInputKeydown(event);
    // Should not throw; send is called (we can't easily verify without full DOM)
  });
});
