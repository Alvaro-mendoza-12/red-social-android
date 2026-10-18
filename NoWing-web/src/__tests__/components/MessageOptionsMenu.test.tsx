import {render,screen,fireEvent,waitFor} from '@testing-library/react'
import {MessageOptionsMenu,MessageReactionBar} from '../../components/MessageOptionsMenu'

test('opening another message closes the first; outside click and Escape close the active menu',async()=>{
 const {container}=render(<><MessageOptionsMenu mine={false}><button>Copiar primero</button></MessageOptionsMenu><MessageOptionsMenu mine><button>Copiar segundo</button></MessageOptionsMenu></>)
 const menus=container.querySelectorAll('details')
 menus[0].open=true;fireEvent(menus[0],new Event('toggle'));menus[1].open=true;fireEvent(menus[1],new Event('toggle'));await waitFor(()=>expect(menus[0].open).toBe(false));expect(menus[1].open).toBe(true)
 fireEvent.keyDown(document,{key:'Escape'});expect(menus[1].open).toBe(false)
 menus[0].open=true;fireEvent.pointerDown(document.body);expect(menus[0].open).toBe(false)
})
test('reactions are a labeled horizontal group and choosing one closes the menu',()=>{
 const react=jest.fn();const {container}=render(<MessageOptionsMenu mine><MessageReactionBar onReact={react}/></MessageOptionsMenu>)
 const menu=container.querySelector('details')!;menu.open=true
 expect(screen.getByRole('group',{name:'Reacciones rápidas'})).toBeInTheDocument();expect(screen.getAllByRole('button')).toHaveLength(6)
 fireEvent.click(screen.getByRole('button',{name:'Reaccionar con ❤️'}));expect(react).toHaveBeenCalledWith('❤️');expect(menu.open).toBe(false)
})


test('keeps a menu inside its chat when a sidebar occupies the left edge', async()=>{
 const {container}=render(<div className="chat-thread"><MessageOptionsMenu mine={false}><button>Copiar imagen</button></MessageOptionsMenu></div>);
 const chat=container.querySelector('.chat-thread')!,menu=container.querySelector('details')!,popover=container.querySelector<HTMLElement>('.message-menu-popover')!;
 const bounds=(left:number,top:number,width:number,height:number)=>({left,top,right:left+width,bottom:top+height,width,height,x:left,y:top,toJSON:()=>({})});
 jest.spyOn(chat,'getBoundingClientRect').mockReturnValue(bounds(400,60,500,600));
 jest.spyOn(menu,'getBoundingClientRect').mockReturnValue(bounds(450,100,26,26));
 jest.spyOn(popover,'getBoundingClientRect').mockReturnValue(bounds(220,128,236,250));
 menu.open=true;fireEvent(menu,new Event('toggle'));
 await waitFor(()=>expect(popover.style.transform).toBe('translate(188px,0px)'));
 expect(popover.style.width).toBe('236px');
});
